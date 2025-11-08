import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

interface Proc {
    OutputStream stdin();

    InputStream stdout();

    InputStream stderr();

    void start() throws Exception;

    int waitFor() throws InterruptedException;
}

final class ExternalProc implements Proc {
    private final ProcessBuilder pb;
    private Process p;

    ExternalProc(List<String> argv, File cwd, Map<String, String> env) {
        this.pb = new ProcessBuilder(argv).directory(cwd);
        if (env != null) pb.environment().putAll(env);
    }

    @Override
    public OutputStream stdin() {
        return p.getOutputStream();
    }

    @Override
    public InputStream stdout() {
        return p.getInputStream();
    }

    @Override
    public InputStream stderr() {
        return p.getErrorStream();
    }

    @Override
    public void start() throws IOException {
        p = pb.start();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return p.waitFor();
    }
}

final class BuiltinProc implements Proc {
    private final String name;
    private final List<String> args;
    private final Builtins impl;

    private final PipedOutputStream inWriter = new PipedOutputStream();
    private final PipedInputStream inReader;
    private final PipedOutputStream outWriter = new PipedOutputStream();
    private final PipedInputStream outReader;
    private final PipedOutputStream errWriter = new PipedOutputStream();
    private final PipedInputStream errReader;

    private Thread worker;
    private volatile int exitCode = 0;

    BuiltinProc(String name, List<String> args, Builtins impl) throws IOException {
        this.name = name;
        this.args = args;
        this.impl = impl;
        this.inReader = new PipedInputStream(inWriter);
        this.outReader = new PipedInputStream(outWriter);
        this.errReader = new PipedInputStream(errWriter);
    }

    @Override
    public OutputStream stdin() {
        return inWriter;
    }

    @Override
    public InputStream stdout() {
        return outReader;
    }

    @Override
    public InputStream stderr() {
        return errReader;
    }

    @Override
    public void start() {
        worker =
                new Thread(
                        () -> {
                            try {
                                exitCode =
                                        impl.runBuiltin(name, args, inReader, outWriter, errWriter);
                            } catch (Exception e) {
                                try {
                                    errWriter.write(
                                            ("internal error: " + e.getMessage() + "\n")
                                                    .getBytes());
                                } catch (IOException ignore) {
                                }
                                exitCode = 1;
                            } finally {
                                closeQuietly(outWriter);
                                closeQuietly(errWriter);
                                closeQuietly(inReader);
                            }
                        },
                        "builtin-" + name);
        worker.start();
    }

    @Override
    public int waitFor() throws InterruptedException {
        worker.join();
        return exitCode;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null)
            try {
                c.close();
            } catch (Exception ignore) {
            }
    }
}

final class Builtins {
    static String find(String str) {
        String path = System.getenv("PATH");
        if (path == null) return str + ": not found";
        String[] commands = path.split(File.pathSeparator);
        String ans = str + ": not found";
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], str);
            if (file.exists() && file.canExecute()) {
                return str + " is " + file.getAbsolutePath();
            }
        }
        return ans;
    }

    private static boolean isBuiltin(String s) {
        return s.equals("echo")
                || s.equals("exit")
                || s.equals("pwd")
                || s.equals("type")
                || s.equals("cd")
                || s.equals("history");
    }

    int runBuiltin(
            String name, List<String> args, InputStream in, OutputStream out, OutputStream err)
            throws IOException {
        switch (name) {
            case "echo":
                {
                    out.write(String.join(" ", args).getBytes());
                    out.write('\n');
                    out.flush();
                    return 0;
                }
            case "pwd":
                {
                    out.write((System.getProperty("user.dir") + "\n").getBytes());
                    out.flush();
                    return 0;
                }
            case "type":
                {
                    if (args.isEmpty()) return 0;
                    int rc = 0;
                    for (String target : args) {
                        if (isBuiltin(target)) {
                            out.write((target + " is a shell builtin\n").getBytes());
                        } else {
                            String path = find(target);
                            if (!path.endsWith("not found")) {
                                out.write((path + "\n").getBytes());
                            } else {
                                out.write((target + " not found\n").getBytes());
                                rc = 1;
                            }
                        }
                    }
                    out.flush();
                    return rc;
                }
            case "cd":
                {
                    if (args.isEmpty()) {
                        String home = System.getenv("HOME");
                        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
                        if (home != null && !home.isEmpty()) {
                            System.setProperty("user.dir", home);
                            return 0;
                        }
                        err.write("cd: HOME not set\n".getBytes());
                        err.flush();
                        return 1;
                    }
                    if (args.size() > 1) {
                        err.write("cd: too many arguments\n".getBytes());
                        err.flush();
                        return 1;
                    }
                    String pathArg = args.get(0);

                    if (!pathArg.isEmpty() && pathArg.charAt(0) == '~') {
                        String home = System.getenv("HOME");
                        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
                        if (home != null && !home.isEmpty()) {
                            System.setProperty("user.dir", home);
                            return 0;
                        }
                        err.write("cd: HOME not set\n".getBytes());
                        err.flush();
                        return 1;
                    }
                    if (!pathArg.isEmpty() && pathArg.charAt(0) == '.') {
                        change(pathArg);
                        return 0;
                    }
                    String abs = exists(pathArg);
                    if (abs != null) {
                        System.setProperty("user.dir", abs);
                        return 0;
                    }
                    String relTry = exists(System.getProperty("user.dir") + "/" + pathArg);
                    if (relTry != null) {
                        System.setProperty("user.dir", relTry);
                        return 0;
                    }
                    err.write(("cd: " + pathArg + ": No such file or directory\n").getBytes());
                    err.flush();
                    return 1;
                }
            default:
                {
                    err.write((name + ": not a builtin\n").getBytes());
                    err.flush();
                    return 1;
                }
        }
    }

    public static String exists(String input) {
        Path path = Path.of(input);
        File file = path.toFile();
        if (file.exists() && file.isDirectory()) {
            return file.getAbsolutePath();
        }
        return null;
    }

    static void change(String input) {
        String path = System.getProperty("user.dir");
        Deque<String> dq = new ArrayDeque<>();
        String[] paths = path.split("/");
        for (int i = 0; i < paths.length; i++) dq.offer(paths[i]);
        dq.pollFirst();
        int ind = 0;
        while (ind < input.length()) {
            if (ind + 2 < input.length() && input.substring(ind, ind + 3).equals("../")) {
                dq.pollLast();
                ind += 3;
                continue;
            } else if (input.charAt(ind) == '/') {
                ind++;
                StringBuilder sb = new StringBuilder("");
                while (ind < input.length() && input.charAt(ind) != '/') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offer(sb.toString());
            } else {
                ind++;
            }
        }
        StringBuilder sb = new StringBuilder("");
        while (dq.size() > 0) {
            sb.append('/');
            sb.append(dq.peekFirst());
            dq.pollFirst();
        }
        System.setProperty("user.dir", sb.toString());
    }
}

class TrieNode {
    TrieNode[] children = new TrieNode[128];
    boolean isEndOfWord = false;
}

class Trie {
    private final TrieNode root;

    public Trie() {
        root = new TrieNode();
    }

    public void insert(String word) {
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            if (current.children[index] == null) current.children[index] = new TrieNode();
            current = current.children[index];
        }
        current.isEndOfWord = true;
    }

    public String search(String word) {
        StringBuilder result = new StringBuilder();
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            if (current.children[index] == null) return "";
            current = current.children[index];
        }
        while (true) {
            if (current.isEndOfWord) break;
            int c = 0;
            for (int i = 0; i < 128; i++) if (current.children[i] != null) c++;
            if (c > 1) return "";
            for (int i = 0; i < 128; i++) {
                if (current.children[i] != null) {
                    result.append((char) i);
                    current = current.children[i];
                    break;
                }
            }
        }
        return result.toString();
    }

    public boolean checkComplete(String word) {
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            current = current.children[index];
        }
        for (int i = 0; i < 128; i++) if (current.children[i] != null) return false;
        return true;
    }
}

public class Main {
    static final int KEY_UP = -1001;
    static final int KEY_DOWN = -1002;
    static final int KEY_RIGHT = -1003;
    static final int KEY_LEFT = -1004;
    static final int KEY_ENTER = -1005;
    static final int KEY_BACKSPACE = -1006;

    static int readKey(java.io.PushbackInputStream in) throws java.io.IOException {
        int b = in.read();
        if (b == -1) return -1;

        if (b == 0x1B) {
            int b1 = in.read();
            if (b1 == -1) return 0x1B;
            if (b1 == '[' || b1 == 'O') {
                int b2 = in.read();
                if (b2 == -1) return 0x1B;
                switch (b2) {
                    case 'A':
                        return KEY_UP;
                    case 'B':
                        return KEY_DOWN;
                    case 'C':
                        return KEY_RIGHT;
                    case 'D':
                        return KEY_LEFT;
                    default:
                        if (Character.isDigit(b2)) {
                            int x;
                            while ((x = in.read()) != -1 && Character.isDigit(x)) {}
                            if (x != '~' && x != -1) in.unread(x);
                        }
                        return 0x1B;
                }
            } else {
                in.unread(b1);
                return 0x1B;
            }
        } else if (b == 127) {
            return KEY_BACKSPACE;
        } else if (b == '\r' || b == '\n') {
            return KEY_ENTER;
        }
        return b;
    }

    public static void main(String[] args) throws Exception {
        if (System.console() != null) {
            ProcessBuilder processBuilder =
                    new ProcessBuilder("/bin/sh", "-c", "stty -echo -icanon min 1 < /dev/tty");
            processBuilder.directory(new File("").getCanonicalFile());
            Process rawMode = processBuilder.start();
            rawMode.waitFor();
        }
        System.out.print("$ ");
        Builtins sharedBuiltins = new Builtins();
        List<String> commands = new ArrayList<>();
        Deque<String> dq = new ArrayDeque<>();
        HashMap<Path, Integer> tracker = new HashMap<>();
        if (System.getenv().containsKey("HISTFILE")) {
            try (BufferedReader br =
                    Files.newBufferedReader(
                            getPath(System.getenv("HISTFILE")), StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isEmpty()) {
                        commands.add(line);
                    }
                }
            }
        }
        try (java.io.PushbackInputStream pin = new java.io.PushbackInputStream(System.in, 8); ) {
            StringBuilder sb = new StringBuilder();
            while (true) {
                Trie trie = new Trie();
                addToTrie(trie);
                boolean firstTab = false;
                sb.setLength(0);
                while (true) {
                    int ch = readKey(pin);
                    if (ch == KEY_UP) {
                        dq.offerFirst(commands.get(commands.size() - 1));
                        commands.removeLast();
                        sb.setLength(0);
                        sb.append(dq.peekFirst());
                        System.out.print("\r\u001B[2K");
                        System.out.print("$ " + sb.toString());
                        continue;
                    } else if (ch == KEY_DOWN) {
                        commands.add(dq.peekFirst());
                        dq.pollFirst();
                        sb.setLength(0);
                        sb.append(dq.peekFirst());
                        System.out.print("\r\u001B[2K");
                        System.out.print("$ " + sb.toString());
                        continue;
                    } else if (ch == '\t') {
                        String str = sb.toString();
                        if (str.equals("e")) {
                            sb.append("cho ");
                            System.out.print("cho ");
                        } else if (str.equals("ec")) {
                            sb.append("ho ");
                            System.out.print("ho ");
                        } else if (str.equals("ech")) {
                            sb.append("o ");
                            System.out.print("o ");
                        } else if (str.equals("ex")) {
                            sb.append("it ");
                            System.out.print("it ");
                        } else if (str.equals("exi")) {
                            sb.append("t ");
                            System.out.print("t ");
                        } else {
                            String file = trie.search(str);
                            if (file.isEmpty()) {
                                List<String> files = fileOnTab(str);
                                if (files == null || files.size() == 0) {
                                    System.out.println((char) 7);
                                    continue;
                                }
                                if (files.size() == 1) {
                                    sb.append(files.get(0).substring(str.length()) + " ");
                                    System.out.print(files.get(0).substring(str.length()) + " ");
                                    continue;
                                }
                                if (!firstTab) {
                                    firstTab = true;
                                    System.out.println((char) 7);
                                    continue;
                                }
                                for (String file1 : files) System.out.print(file1 + "  ");
                                System.out.println();
                                System.out.print("$ " + sb.toString());
                                continue;
                            }
                            sb.append(file);
                            System.out.print(file);
                            if (trie.checkComplete(sb.toString())) {
                                System.out.print(" ");
                                sb.append(" ");
                            }
                        }
                    } else if (ch == KEY_ENTER) {
                        System.out.println();
                        break;
                    } else if (ch == KEY_BACKSPACE) {
                        if (!sb.isEmpty()) {
                            System.out.print("\b \b");
                            sb.deleteCharAt(sb.length() - 1);
                        }
                    } else {
                        sb.append((char) ch);
                        System.out.print((char) ch);
                        System.out.flush();
                    }
                }
                String input = sb.toString();
                commands.add(input);
                if (input.equals("history")) {
                    for (int i = 0; i < commands.size(); i++) {
                        System.out.println(i + 1 + " " + commands.get(i));
                    }
                    System.out.print("$ ");
                    continue;
                }
                if (input.startsWith("history")) {
                    String[] inputs = input.split(" ");
                    if (inputs.length > 1 && inputs[1].equals("-r")) {
                        try (BufferedReader br =
                                Files.newBufferedReader(
                                        getPath(inputs[2]), StandardCharsets.UTF_8)) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                commands.add(line);
                            }
                        }
                        System.out.print("$ ");
                        continue;
                    }
                    if (inputs.length > 1 && inputs[1].equals("-w")) {
                        StringBuilder write = new StringBuilder();
                        for (String command : commands) {
                            write.append(command + "\n");
                        }
                        Files.writeString(
                                getPath(inputs[2]), write.toString(), StandardCharsets.UTF_8);
                        System.out.print("$ ");
                        continue;
                    }
                    if (inputs.length > 1 && inputs[1].equals("-a")) {
                        int ind = 0;
                        Path path = getPath(inputs[2]);
                        if (tracker.containsKey(path)) {
                            ind = tracker.get(path);
                        }
                        StringBuilder write = new StringBuilder();
                        for (int i = ind; i < commands.size(); i++) {
                            write.append(commands.get(i) + "\n");
                        }
                        Files.writeString(
                                path,
                                write.toString(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                        tracker.put(path, commands.size());
                        System.out.print("$ ");
                        continue;
                    }
                    for (int i = commands.size() - Integer.parseInt(inputs[1]);
                            i < commands.size();
                            i++) {
                        System.out.println(i + 1 + " " + commands.get(i));
                    }
                    System.out.print("$ ");
                    continue;
                }
                if (splitPipeline(input).size() > 1) {
                    usePipe(input, sharedBuiltins);
                    System.out.print("$ ");
                    continue;
                }

                boolean flag = false, error = false, append = false;
                for (int i = 0; i < input.length(); i++) {
                    if (input.charAt(i) == '>') {
                        if (i - 1 >= 0 && input.charAt(i - 1) == '2') {
                            error = true;
                            input = input.substring(0, i - 1) + input.substring(i);
                        }
                        flag = true;
                        break;
                    }
                }
                for (int i = 0; i < input.length(); i++) {
                    if (input.charAt(i) == '>') {
                        if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                            append = true;
                            input = input.substring(0, i) + input.substring(i + 1);
                        }
                        flag = true;
                        break;
                    }
                }
                if (flag) {
                    redirect(input, error, append);
                    System.out.print("$ ");
                    continue;
                }

                if (input.split(" ")[0].equals("echo")) {
                    System.out.println(print(input.substring(5)));
                    System.out.print("$ ");
                    continue;
                }
                if (input.split(" ")[0].equals("cat")) {
                    String[] files = convert(input);
                    if (files.length > 0) {
                        for (int i = 0; i < files.length; i++) content(files[i]);
                    }
                    System.out.print("$ ");
                    continue;
                }
                if (input.charAt(0) == '\'') {
                    int ind = 1;
                    for (int i = 1; i < input.length(); i++) {
                        if (input.charAt(i) == '\'') {
                            ind = i;
                            break;
                        }
                    }
                    content(input.substring(ind + 2));
                    System.out.print("$ ");
                    continue;
                }
                if (input.charAt(0) == '"') {
                    int ind = 1;
                    for (int i = 1; i < input.length(); i++) {
                        if (input.charAt(i) == '"') {
                            ind = i;
                            break;
                        }
                    }
                    content(input.substring(ind + 2));
                    System.out.print("$ ");
                    continue;
                }
                if (input.split(" ")[0].equals("cd")) {
                    String path = input.split(" ")[1];
                    if (path.charAt(0) == '.') {
                        change(path);
                        System.out.print("$ ");
                        continue;
                    }
                    if (path.charAt(0) == '~') {
                        System.setProperty("user.dir", System.getenv("HOME"));
                        System.out.print("$ ");
                        continue;
                    }
                    path = exists(path);
                    if (path != null) {
                        System.setProperty("user.dir", path);
                    } else {
                        System.out.println(
                                "cd: " + input.split(" ")[1] + ": No such file or directory");
                    }
                    System.out.print("$ ");
                    continue;
                }
                if (input.equals("pwd")) {
                    System.out.println(System.getProperty("user.dir"));
                    System.out.print("$ ");
                    continue;
                }
                if (input.split(" ")[0].equals("pwd")) {
                    System.out.println(Path.of("").toAbsolutePath());
                    System.out.print("$ ");
                    continue;
                }
                if (check(input)) {
                    List<String> argv = tokenizeArgs(input);
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new File(System.getProperty("user.dir")));
                    Process process = pb.start();
                    process.getInputStream().transferTo(System.out);
                    System.out.print("$ ");
                    continue;
                }

                String str1 = input.length() >= 4 ? input.substring(0, 4) : input;
                if (str1.equals("type")) {
                    String str = input.length() > 5 ? input.substring(5) : "";
                    if (str.equals("echo")
                            || str.equals("exit")
                            || str.equals("pwd")
                            || str.equals("type")
                            || str.equals("history")) {
                        System.out.println(str + " is a shell builtin");
                    } else {
                        System.out.println(find(str));
                    }
                } else if (str1.equals("echo")) {
                    System.out.println(input.substring(5));
                } else if (str1.equals("exit")) {
                    if (System.getenv().containsKey("HISTFILE")) {
                        StringBuilder write = new StringBuilder();
                        for (String command : commands) {
                            write.append(command + "\n");
                        }
                        Files.writeString(
                                getPath(System.getenv("HISTFILE")),
                                write.toString(),
                                StandardCharsets.UTF_8);
                    }
                    System.exit(0);
                } else {
                    System.out.println(input + ": not found");
                }
                System.out.print("$ ");
            }
        }
    }

    static void usePipe(String input, Builtins sharedBuiltins) throws Exception {
        List<List<String>> segments = splitPipeline(input);
        List<Proc> processList = new ArrayList<>();

        for (List<String> seg : segments) {
            if (seg.isEmpty()) continue;
            String cmd = seg.get(0);
            if (cmd.equals("echo") || cmd.equals("type") || cmd.equals("cd") || cmd.equals("pwd")) {
                processList.add(new BuiltinProc(cmd, seg.subList(1, seg.size()), sharedBuiltins));
            } else {
                // Pass full argv to external
                processList.add(
                        new ExternalProc(
                                seg, new File(System.getProperty("user.dir")), System.getenv()));
            }
        }
        startPipe(processList);
    }

    static int startPipe(List<Proc> ps) throws Exception {
        if (ps.isEmpty()) return 0;

        for (Proc p : ps) p.start();

        List<Thread> pumps = new ArrayList<>();
        for (int i = 0; i < ps.size() - 1; i++) {
            pumps.add(pump(ps.get(i).stdout(), ps.get(i + 1).stdin(), true));
        }
        Thread lastOut = pump(ps.get(ps.size() - 1).stdout(), System.out, false);

        List<Thread> errPumps = new ArrayList<>();
        for (Proc p : ps) errPumps.add(pump(p.stderr(), System.err, false));

        closeQuietly(ps.get(0).stdin());

        int code = 0;
        for (Proc p : ps) code = p.waitFor();

        for (Thread t : pumps) t.join();
        lastOut.join();
        for (Thread t : errPumps) t.join();

        return code;
    }

    static Thread pump(InputStream in, OutputStream out, boolean closeDest) {
        Thread t =
                new Thread(
                        () -> {
                            final byte[] buf = new byte[8192];
                            try {
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                    out.flush();
                                }
                            } catch (IOException ignored) {
                            } finally {
                                if (closeDest) closeQuietly(out);
                                closeQuietly(in);
                            }
                        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    static List<List<String>> splitPipeline(String s) {
        ArrayList<List<String>> out = new ArrayList<>();
        ArrayList<String> cur = new ArrayList<>();
        StringBuilder tok = new StringBuilder();
        boolean inS = false, inD = false, esc = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                tok.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '\'' && !inD) {
                inS = !inS;
                continue;
            }
            if (c == '"' && !inS) {
                inD = !inD;
                continue;
            }

            if (c == '|' && !inS && !inD) {
                pushTok(cur, tok);
                if (!cur.isEmpty()) out.add(new ArrayList<>(cur));
                cur.clear();
                continue;
            }
            if (Character.isWhitespace(c) && !inS && !inD) {
                pushTok(cur, tok);
                continue;
            }
            tok.append(c);
        }
        pushTok(cur, tok);
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    static void pushTok(List<String> cur, StringBuilder tok) {
        if (tok.length() > 0) {
            cur.add(tok.toString());
            tok.setLength(0);
        }
    }

    static void addToTrie(Trie trie) {
        String path = System.getenv("PATH");
        if (path == null) return;
        for (String dir : path.split(File.pathSeparator)) {
            File d = new File(dir);
            if (!d.isDirectory()) continue;
            File[] matches = d.listFiles(f -> f.isFile() && f.canExecute());
            if (matches == null || matches.length == 0) continue;
            for (File file : matches) trie.insert(file.getName());
        }
    }

    static void redirect(String input, boolean error, boolean append)
            throws IOException, InterruptedException {
        String[] inputs = input.split(" ");
        if (inputs[0].equals("echo")) {
            Deque<String> dq = new ArrayDeque<>();
            String dest = "";
            for (int i = 0; i < input.length(); ) {
                if (input.charAt(i) == '\'') {
                    int j = i;
                    StringBuilder sb = new StringBuilder();
                    j++;
                    while (j < input.length()) {
                        if (input.charAt(j) == '\'') {
                            i = j + 1;
                            break;
                        }
                        sb.append(input.charAt(j));
                        j++;
                    }
                    dq.offerLast(sb.toString());
                } else if (input.charAt(i) == '\"') {
                    int j = i;
                    StringBuilder sb = new StringBuilder();
                    j++;
                    while (j < input.length()) {
                        if (input.charAt(j) == '\"') {
                            i = j + 1;
                            break;
                        }
                        sb.append(input.charAt(j));
                        j++;
                    }
                    dq.offerLast(sb.toString());
                } else if (i + 1 < input.length()
                        && input.charAt(i) == '1'
                        && input.charAt(i + 1) == '>') {
                    dest = input.substring(i + 3);
                    break;
                } else if (input.charAt(i) == '>') {
                    dest = input.substring(i + 2);
                    break;
                } else {
                    i++;
                }
            }
            dest = dest.trim();
            if ((dest.startsWith("\"") && dest.endsWith("\""))
                    || (dest.startsWith("'") && dest.endsWith("'"))) {
                dest = dest.substring(1, dest.length() - 1);
            }
            StringBuilder outBuf = new StringBuilder();
            for (var it = dq.iterator(); it.hasNext(); ) {
                outBuf.append(it.next());
                if (it.hasNext()) outBuf.append(' ');
            }
            Path outPath = Path.of(dest);
            Path parent = outPath.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            if (error) {
                System.out.println(outBuf.toString());
                if (append) {
                    Files.write(
                            outPath,
                            "".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                } else {
                    Files.write(
                            outPath,
                            "".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
            } else {
                outBuf.append('\n');
                if (append) {
                    Files.write(
                            outPath,
                            outBuf.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                } else {
                    Files.write(
                            outPath,
                            outBuf.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
        } else if (inputs[0].equals("ls")) {
            int gt = input.lastIndexOf('>');
            if (gt != -1) {
                String lhs = input.substring(0, gt).trim();
                String right = input.substring(gt + 1).trim();
                String dest = unquote(firstToken(right));
                java.nio.file.Path out = java.nio.file.Path.of(dest);
                java.nio.file.Path parent = out.getParent();
                if (parent != null && !java.nio.file.Files.exists(parent)) {
                    java.nio.file.Files.createDirectories(parent);
                }
                String[] argv = lhs.split("\\s+");
                String exe = resolveOnPath(argv[0]);
                if (exe == null) {
                    System.out.println(argv[0] + ": not found");
                } else {
                    argv[0] = exe;
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new File(System.getProperty("user.dir")));
                    if (error) {
                        if (append)
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile()));
                        else pb.redirectError(ProcessBuilder.Redirect.to(out.toFile()));
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    } else {
                        if (append)
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
                        else pb.redirectOutput(out.toFile());
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    Process p = pb.start();
                    p.waitFor();
                }
            } else {
                ProcessBuilder pb = new ProcessBuilder(input.trim().split("\\s+"));
                pb.directory(new java.io.File(System.getProperty("user.dir")));
                pb.inheritIO();
                Process p = pb.start();
                p.waitFor();
            }
        } else if (inputs[0].equals("cat")) {
            int gt = input.lastIndexOf('>');
            if (gt == -1) return;
            int j = gt - 1;
            while (j >= 0 && Character.isWhitespace(input.charAt(j))) j--;
            boolean oneRedir =
                    (j >= 0
                            && input.charAt(j) == '1'
                            && !(j - 1 >= 0 && input.charAt(j - 1) == '-'));
            String lhs = input.substring(0, oneRedir ? j : gt).trim();
            String rhs = input.substring(gt + 1).trim();
            String dest = unquote(firstToken(rhs));
            Path out = Path.of(dest);
            Path parent = out.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            String srcPart = lhs.startsWith("cat") ? lhs.substring(3).trim() : lhs;
            List<String> sources = tokenizeArgs(srcPart);
            if (sources.isEmpty()) {
                Files.write(
                        out,
                        new byte[0],
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                return;
            }
            List<String> argv = new ArrayList<>();
            String catExe = resolveOnPath("cat");
            argv.add(catExe != null ? catExe : "cat");
            argv.addAll(sources);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(new File(System.getProperty("user.dir")));
            if (error) {
                if (append) pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile()));
                else pb.redirectError(ProcessBuilder.Redirect.to(out.toFile()));
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            } else {
                if (append) pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
                else pb.redirectOutput(out.toFile());
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            p.waitFor();
        }
    }

    static String firstToken(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    static String unquote(String s) {
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static List<String> fileOnTab(String str) {
        List<String> files = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) return files;
        for (String dir : path.split(File.pathSeparator)) {
            File d = new File(dir);
            if (!d.isDirectory()) continue;
            File[] matches =
                    d.listFiles(f -> f.isFile() && f.getName().startsWith(str) && f.canExecute());
            if (matches == null || matches.length == 0) continue;
            for (File file : matches) files.add(file.getName());
        }
        Collections.sort(files);
        return files;
    }

    static Path getPath(String name) {
        if (name.contains(java.io.File.separator)) return new java.io.File(name).toPath();
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, name);
            if (f.isFile() && f.canExecute()) return f.toPath();
        }
        return new java.io.File(name).toPath();
    }

    static String resolveOnPath(String name) {
        if (name.contains(java.io.File.separator)) return new java.io.File(name).getAbsolutePath();
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, name);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    static String[] convert(String input) {
        Deque<String> response = new ArrayDeque<>();
        int ind = 0;
        while (ind < input.length()) {
            if (input.charAt(ind) == '/') {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && (input.charAt(ind) != ' ')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\'') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && (input.charAt(ind) != '\'')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\"') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && (input.charAt(ind) != '\"')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            }
            ind++;
        }
        return response.toArray(new String[response.size()]);
    }

    static void content(String file) {
        Process p;
        try {
            p = new ProcessBuilder("cat", file).redirectErrorStream(true).start();
            p.getInputStream().transferTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static String print(String input) {
        Deque<String> dq = new ArrayDeque<>();
        int ind = 0;
        while (ind < input.length()) {
            if (input.charAt(ind) == '"') {
                StringBuilder sb = new StringBuilder();
                ind++;
                while (ind != input.length() && input.charAt(ind) != '"') {
                    if (input.charAt(ind) == '\\' && ind + 1 < input.length()) {
                        sb.append(input.charAt(ind + 1));
                        ind += 2;
                        continue;
                    }
                    sb.append(input.charAt(ind));
                    ind++;
                }
                ind++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\'') {
                StringBuilder sb = new StringBuilder();
                ind++;
                while (ind != input.length() && input.charAt(ind) != '\'') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                ind++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\\') {
                dq.offerLast(new StringBuilder("").append(input.charAt(ind + 1)).toString());
                ind += 2;
                continue;
            } else if (input.charAt(ind) == ' ') {
                if (dq.size() > 0 && dq.peekLast().equals(" ")) {
                    ind++;
                    continue;
                }
                dq.offerLast(" ");
                ind++;
            } else {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length()
                        && input.charAt(ind) != ' '
                        && input.charAt(ind) != '\''
                        && input.charAt(ind) != '"'
                        && input.charAt(ind) != '\\') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offerLast(sb.toString());
            }
        }
        StringBuilder sb = new StringBuilder();
        while (dq.size() > 0) {
            sb.append(dq.peekFirst());
            dq.pollFirst();
        }
        return sb.toString();
    }

    static List<String> tokenizeArgs(String s) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inS = false, inD = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '\'' && !inD) {
                inS = !inS;
                continue;
            }
            if (c == '"' && !inS) {
                inD = !inD;
                continue;
            }
            if (Character.isWhitespace(c) && !inS && !inD) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static void change(String input) {
        String path = System.getProperty("user.dir");
        Deque<String> dq = new ArrayDeque<>();
        String[] paths = path.split("/");
        for (int i = 0; i < paths.length; i++) dq.offer(paths[i]);
        dq.pollFirst();
        int ind = 0;
        while (ind < input.length()) {
            if (ind + 2 < input.length() && input.substring(ind, ind + 3).equals("../")) {
                dq.pollLast();
                ind += 3;
                continue;
            } else if (input.charAt(ind) == '/') {
                ind++;
                StringBuilder sb = new StringBuilder("");
                while (ind < input.length() && input.charAt(ind) != '/') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offer(sb.toString());
            } else {
                ind++;
            }
        }
        StringBuilder sb = new StringBuilder("");
        while (dq.size() > 0) {
            sb.append('/');
            sb.append(dq.peekFirst());
            dq.pollFirst();
        }
        System.setProperty("user.dir", sb.toString());
    }

    public static String exists(String input) {
        Path path = Path.of(input);
        File file = path.toFile();
        if (file.exists() && file.isDirectory()) return file.getAbsolutePath();
        return null;
    }

    static boolean check(String input) {
        List<String> toks = tokenizeArgs(input);
        if (toks.isEmpty()) return false;
        String cmd = toks.get(0);
        String path = System.getenv("PATH");
        if (path == null) return false;
        String[] commands = path.split(File.pathSeparator);
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], cmd);
            if (file.exists() && file.canExecute()) return true;
        }
        return false;
    }

    static String find(String str) {
        String path = System.getenv("PATH");
        if (path == null) return str + ": not found";
        String[] commands = path.split(File.pathSeparator);
        String ans = str + ": not found";
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], str);
            if (file.exists() && file.canExecute()) return str + " is " + file.getAbsolutePath();
        }
        return ans;
    }
}
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

interface Proc {
    OutputStream stdin();

    InputStream stdout();

    InputStream stderr();

    void start() throws Exception;

    int waitFor() throws InterruptedException;
}

final class ExternalProc implements Proc {
    private final ProcessBuilder pb;
    private Process p;

    ExternalProc(List<String> argv, File cwd, Map<String, String> env) {
        this.pb = new ProcessBuilder(argv).directory(cwd);
        if (env != null) pb.environment().putAll(env);
    }

    @Override
    public OutputStream stdin() {
        return p.getOutputStream();
    }

    @Override
    public InputStream stdout() {
        return p.getInputStream();
    }

    @Override
    public InputStream stderr() {
        return p.getErrorStream();
    }

    @Override
    public void start() throws IOException {
        p = pb.start();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return p.waitFor();
    }
}

final class BuiltinProc implements Proc {
    private final String name;
    private final List<String> args;
    private final Builtins impl;

    private final PipedOutputStream inWriter = new PipedOutputStream();
    private final PipedInputStream inReader;
    private final PipedOutputStream outWriter = new PipedOutputStream();
    private final PipedInputStream outReader;
    private final PipedOutputStream errWriter = new PipedOutputStream();
    private final PipedInputStream errReader;

    private Thread worker;
    private volatile int exitCode = 0;

    BuiltinProc(String name, List<String> args, Builtins impl) throws IOException {
        this.name = name;
        this.args = args;
        this.impl = impl;
        this.inReader = new PipedInputStream(inWriter);
        this.outReader = new PipedInputStream(outWriter);
        this.errReader = new PipedInputStream(errWriter);
    }

    @Override
    public OutputStream stdin() {
        return inWriter;
    }

    @Override
    public InputStream stdout() {
        return outReader;
    }

    @Override
    public InputStream stderr() {
        return errReader;
    }

    @Override
    public void start() {
        worker =
                new Thread(
                        () -> {
                            try {
                                exitCode =
                                        impl.runBuiltin(name, args, inReader, outWriter, errWriter);
                            } catch (Exception e) {
                                try {
                                    errWriter.write(
                                            ("internal error: " + e.getMessage() + "\n")
                                                    .getBytes());
                                } catch (IOException ignore) {
                                }
                                exitCode = 1;
                            } finally {
                                closeQuietly(outWriter);
                                closeQuietly(errWriter);
                                closeQuietly(inReader);
                            }
                        },
                        "builtin-" + name);
        worker.start();
    }

    @Override
    public int waitFor() throws InterruptedException {
        worker.join();
        return exitCode;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null)
            try {
                c.close();
            } catch (Exception ignore) {
            }
    }
}

final class Builtins {
    static String find(String str) {
        String path = System.getenv("PATH");
        if (path == null) return str + ": not found";
        String[] commands = path.split(File.pathSeparator);
        String ans = str + ": not found";
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], str);
            if (file.exists() && file.canExecute()) {
                return str + " is " + file.getAbsolutePath();
            }
        }
        return ans;
    }

    private static boolean isBuiltin(String s) {
        return s.equals("echo")
                || s.equals("exit")
                || s.equals("pwd")
                || s.equals("type")
                || s.equals("cd")
                || s.equals("history");
    }

    int runBuiltin(
            String name, List<String> args, InputStream in, OutputStream out, OutputStream err)
            throws IOException {
        switch (name) {
            case "echo":
                {
                    out.write(String.join(" ", args).getBytes());
                    out.write('\n');
                    out.flush();
                    return 0;
                }
            case "pwd":
                {
                    out.write((System.getProperty("user.dir") + "\n").getBytes());
                    out.flush();
                    return 0;
                }
            case "type":
                {
                    if (args.isEmpty()) return 0;
                    int rc = 0;
                    for (String target : args) {
                        if (isBuiltin(target)) {
                            out.write((target + " is a shell builtin\n").getBytes());
                        } else {
                            String path = find(target);
                            if (!path.endsWith("not found")) {
                                out.write((path + "\n").getBytes());
                            } else {
                                out.write((target + " not found\n").getBytes());
                                rc = 1;
                            }
                        }
                    }
                    out.flush();
                    return rc;
                }
            case "cd":
                {
                    if (args.isEmpty()) {
                        String home = System.getenv("HOME");
                        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
                        if (home != null && !home.isEmpty()) {
                            System.setProperty("user.dir", home);
                            return 0;
                        }
                        err.write("cd: HOME not set\n".getBytes());
                        err.flush();
                        return 1;
                    }
                    if (args.size() > 1) {
                        err.write("cd: too many arguments\n".getBytes());
                        err.flush();
                        return 1;
                    }
                    String pathArg = args.get(0);

                    if (!pathArg.isEmpty() && pathArg.charAt(0) == '~') {
                        String home = System.getenv("HOME");
                        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
                        if (home != null && !home.isEmpty()) {
                            System.setProperty("user.dir", home);
                            return 0;
                        }
                        err.write("cd: HOME not set\n".getBytes());
                        err.flush();
                        return 1;
                    }
                    if (!pathArg.isEmpty() && pathArg.charAt(0) == '.') {
                        change(pathArg);
                        return 0;
                    }
                    String abs = exists(pathArg);
                    if (abs != null) {
                        System.setProperty("user.dir", abs);
                        return 0;
                    }
                    String relTry = exists(System.getProperty("user.dir") + "/" + pathArg);
                    if (relTry != null) {
                        System.setProperty("user.dir", relTry);
                        return 0;
                    }
                    err.write(("cd: " + pathArg + ": No such file or directory\n").getBytes());
                    err.flush();
                    return 1;
                }
            default:
                {
                    err.write((name + ": not a builtin\n").getBytes());
                    err.flush();
                    return 1;
                }
        }
    }

    public static String exists(String input) {
        Path path = Path.of(input);
        File file = path.toFile();
        if (file.exists() && file.isDirectory()) {
            return file.getAbsolutePath();
        }
        return null;
    }

    static void change(String input) {
        String path = System.getProperty("user.dir");
        Deque<String> dq = new ArrayDeque<>();
        String[] paths = path.split("/");
        for (int i = 0; i < paths.length; i++) dq.offer(paths[i]);
        dq.pollFirst();
        int ind = 0;
        while (ind < input.length()) {
            if (ind + 2 < input.length() && input.substring(ind, ind + 3).equals("../")) {
                dq.pollLast();
                ind += 3;
                continue;
            } else if (input.charAt(ind) == '/') {
                ind++;
                StringBuilder sb = new StringBuilder("");
                while (ind < input.length() && input.charAt(ind) != '/') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offer(sb.toString());
            } else {
                ind++;
            }
        }
        StringBuilder sb = new StringBuilder("");
        while (dq.size() > 0) {
            sb.append('/');
            sb.append(dq.peekFirst());
            dq.pollFirst();
        }
        System.setProperty("user.dir", sb.toString());
    }
}

class TrieNode {
    TrieNode[] children = new TrieNode[128];
    boolean isEndOfWord = false;
}

class Trie {
    private final TrieNode root;

    public Trie() {
        root = new TrieNode();
    }

    public void insert(String word) {
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            if (current.children[index] == null) current.children[index] = new TrieNode();
            current = current.children[index];
        }
        current.isEndOfWord = true;
    }

    public String search(String word) {
        StringBuilder result = new StringBuilder();
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            if (current.children[index] == null) return "";
            current = current.children[index];
        }
        while (true) {
            if (current.isEndOfWord) break;
            int c = 0;
            for (int i = 0; i < 128; i++) if (current.children[i] != null) c++;
            if (c > 1) return "";
            for (int i = 0; i < 128; i++) {
                if (current.children[i] != null) {
                    result.append((char) i);
                    current = current.children[i];
                    break;
                }
            }
        }
        return result.toString();
    }

    public boolean checkComplete(String word) {
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            int index = ch;
            current = current.children[index];
        }
        for (int i = 0; i < 128; i++) if (current.children[i] != null) return false;
        return true;
    }
}

public class Main {
    static final int KEY_UP = -1001;
    static final int KEY_DOWN = -1002;
    static final int KEY_RIGHT = -1003;
    static final int KEY_LEFT = -1004;
    static final int KEY_ENTER = -1005;
    static final int KEY_BACKSPACE = -1006;

    static int readKey(java.io.PushbackInputStream in) throws java.io.IOException {
        int b = in.read();
        if (b == -1) return -1;

        if (b == 0x1B) {
            int b1 = in.read();
            if (b1 == -1) return 0x1B;
            if (b1 == '[' || b1 == 'O') {
                int b2 = in.read();
                if (b2 == -1) return 0x1B;
                switch (b2) {
                    case 'A':
                        return KEY_UP;
                    case 'B':
                        return KEY_DOWN;
                    case 'C':
                        return KEY_RIGHT;
                    case 'D':
                        return KEY_LEFT;
                    default:
                        if (Character.isDigit(b2)) {
                            int x;
                            while ((x = in.read()) != -1 && Character.isDigit(x)) {}
                            if (x != '~' && x != -1) in.unread(x);
                        }
                        return 0x1B;
                }
            } else {
                in.unread(b1);
                return 0x1B;
            }
        } else if (b == 127) {
            return KEY_BACKSPACE;
        } else if (b == '\r' || b == '\n') {
            return KEY_ENTER;
        }
        return b;
    }

    public static void main(String[] args) throws Exception {
        if (System.console() != null) {
            ProcessBuilder processBuilder =
                    new ProcessBuilder("/bin/sh", "-c", "stty -echo -icanon min 1 < /dev/tty");
            processBuilder.directory(new File("").getCanonicalFile());
            Process rawMode = processBuilder.start();
            rawMode.waitFor();
        }
        System.out.print("$ ");
        Builtins sharedBuiltins = new Builtins();
        List<String> commands = new ArrayList<>();
        Deque<String> dq = new ArrayDeque<>();
        HashMap<Path, Integer> tracker = new HashMap<>();
        if (System.getenv().containsKey("HISTFILE")) {
            try (BufferedReader br =
                    Files.newBufferedReader(
                            getPath(System.getenv("HISTFILE")), StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isEmpty()) {
                        commands.add(line);
                    }
                }
            }
        }
        try (java.io.PushbackInputStream pin = new java.io.PushbackInputStream(System.in, 8); ) {
            StringBuilder sb = new StringBuilder();
            while (true) {
                Trie trie = new Trie();
                addToTrie(trie);
                boolean firstTab = false;
                sb.setLength(0);
                while (true) {
                    int ch = readKey(pin);
                    if (ch == KEY_UP) {
                        dq.offerFirst(commands.get(commands.size() - 1));
                        commands.removeLast();
                        sb.setLength(0);
                        sb.append(dq.peekFirst());
                        System.out.print("\r\u001B[2K");
                        System.out.print("$ " + sb.toString());
                        continue;
                    } else if (ch == KEY_DOWN) {
                        commands.add(dq.peekFirst());
                        dq.pollFirst();
                        sb.setLength(0);
                        sb.append(dq.peekFirst());
                        System.out.print("\r\u001B[2K");
                        System.out.print("$ " + sb.toString());
                        continue;
                    } else if (ch == '\t') {
                        String str = sb.toString();
                        if (str.equals("e")) {
                            sb.append("cho ");
                            System.out.print("cho ");
                        } else if (str.equals("ec")) {
                            sb.append("ho ");
                            System.out.print("ho ");
                        } else if (str.equals("ech")) {
                            sb.append("o ");
                            System.out.print("o ");
                        } else if (str.equals("ex")) {
                            sb.append("it ");
                            System.out.print("it ");
                        } else if (str.equals("exi")) {
                            sb.append("t ");
                            System.out.print("t ");
                        } else {
                            String file = trie.search(str);
                            if (file.isEmpty()) {
                                List<String> files = fileOnTab(str);
                                if (files == null || files.size() == 0) {
                                    System.out.println((char) 7);
                                    continue;
                                }
                                if (files.size() == 1) {
                                    sb.append(files.get(0).substring(str.length()) + " ");
                                    System.out.print(files.get(0).substring(str.length()) + " ");
                                    continue;
                                }
                                if (!firstTab) {
                                    firstTab = true;
                                    System.out.println((char) 7);
                                    continue;
                                }
                                for (String file1 : files) System.out.print(file1 + "  ");
                                System.out.println();
                                System.out.print("$ " + sb.toString());
                                continue;
                            }
                            sb.append(file);
                            System.out.print(file);
                            if (trie.checkComplete(sb.toString())) {
                                System.out.print(" ");
                                sb.append(" ");
                            }
                        }
                    } else if (ch == KEY_ENTER) {
                        System.out.println();
                        break;
                    } else if (ch == KEY_BACKSPACE) {
                        if (!sb.isEmpty()) {
                            System.out.print("\b \b");
                            sb.deleteCharAt(sb.length() - 1);
                        }
                    } else {
                        sb.append((char) ch);
                        System.out.print((char) ch);
                        System.out.flush();
                    }
                }
                String input = sb.toString();
                commands.add(input);
                if (input.equals("history")) {
                    for (int i = 0; i < commands.size(); i++) {
                        System.out.println(i + 1 + " " + commands.get(i));
                    }
                    System.out.print("$ ");
                    continue;
                }
                if (input.startsWith("history")) {
                    String[] inputs = input.split(" ");
                    if (inputs.length > 1 && inputs[1].equals("-r")) {
                        try (BufferedReader br =
                                Files.newBufferedReader(
                                        getPath(inputs[2]), StandardCharsets.UTF_8)) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                commands.add(line);
                            }
                        }
                        System.out.print("$ ");
                        continue;
                    }
                    if (inputs.length > 1 && inputs[1].equals("-w")) {
                        StringBuilder write = new StringBuilder();
                        for (String command : commands) {
                            write.append(command + "\n");
                        }
                        Files.writeString(
                                getPath(inputs[2]), write.toString(), StandardCharsets.UTF_8);
                        System.out.print("$ ");
                        continue;
                    }
                    if (inputs.length > 1 && inputs[1].equals("-a")) {
                        int ind = 0;
                        Path path = getPath(inputs[2]);
                        if (tracker.containsKey(path)) {
                            ind = tracker.get(path);
                        }
                        StringBuilder write = new StringBuilder();
                        for (int i = ind; i < commands.size(); i++) {
                            write.append(commands.get(i) + "\n");
                        }
                        Files.writeString(
                                path,
                                write.toString(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                        tracker.put(path, commands.size());
                        System.out.print("$ ");
                        continue;
                    }
                    for (int i = commands.size() - Integer.parseInt(inputs[1]);
                            i < commands.size();
                            i++) {
                        System.out.println(i + 1 + " " + commands.get(i));
                    }
                    System.out.print("$ ");
                    continue;
                }
                if (splitPipeline(input).size() > 1) {
                    usePipe(input, sharedBuiltins);
                    System.out.print("$ ");
                    continue;
                }

                boolean flag = false, error = false, append = false;
                for (int i = 0; i < input.length(); i++) {
                    if (input.charAt(i) == '>') {
                        if (i - 1 >= 0 && input.charAt(i - 1) == '2') {
                            error = true;
                            input = input.substring(0, i - 1) + input.substring(i);
                        }
                        flag = true;
                        break;
                    }
                }
                for (int i = 0; i < input.length(); i++) {
                    if (input.charAt(i) == '>') {
                        if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                            append = true;
                            input = input.substring(0, i) + input.substring(i + 1);
                        }
                        flag = true;
                        break;
                    }
                }
                if (flag) {
                    redirect(input, error, append);
                    System.out.print("$ ");
                    continue;
                }

                if (input.split(" ")[0].equals("echo")) {
                    System.out.println(print(input.substring(5)));
                    System.out.print("$ ");
                    continue;
                }
                if (input.split(" ")[0].equals("cat")) {
                    String[] files = convert(input);
                    if (files.length > 0) {
                        for (int i = 0; i < files.length; i++) content(files[i]);
                    }
                    System.out.print("$ ");
                    continue;
                }
                if (input.charAt(0) == '\'') {
                    int ind = 1;
                    for (int i = 1; i < input.length(); i++) {
                        if (input.charAt(i) == '\'') {
                            ind = i;
                            break;
                        }
                    }
                    content(input.substring(ind + 2));
                    System.out.print("$ ");
                    continue;
                }
                if (input.charAt(0) == '"') {
                    int ind = 1;
                    for (int i = 1; i < input.length(); i++) {
                        if (input.charAt(i) == '"') {
                            ind = i;
                            break;
                        }
                    }
                    content(input.substring(ind + 2));
                    System.out.print("$ ");
                    continue;
                }
                if (input.split(" ")[0].equals("cd")) {
                    String path = input.split(" ")[1];
                    if (path.charAt(0) == '.') {
                        change(path);
                        System.out.print("$ ");
                        continue;
                    }
                    if (path.charAt(0) == '~') {
                        System.setProperty("user.dir", System.getenv("HOME"));
                        System.out.print("$ ");
                        continue;
                    }
                    path = exists(path);
                    if (path != null) {
                        System.setProperty("user.dir", path);
                    } else {
                        System.out.println(
                                "cd: " + input.split(" ")[1] + ": No such file or directory");
                    }
                    System.out.print("$ ");
                    continue;
                }
                if (input.equals("pwd")) {
                    System.out.println(System.getProperty("user.dir"));
                    System.out.print("$ ");
                    continue;
                }
                if (input.split(" ")[0].equals("pwd")) {
                    System.out.println(Path.of("").toAbsolutePath());
                    System.out.print("$ ");
                    continue;
                }
                if (check(input)) {
                    List<String> argv = tokenizeArgs(input);
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new File(System.getProperty("user.dir")));
                    Process process = pb.start();
                    process.getInputStream().transferTo(System.out);
                    System.out.print("$ ");
                    continue;
                }

                String str1 = input.length() >= 4 ? input.substring(0, 4) : input;
                if (str1.equals("type")) {
                    String str = input.length() > 5 ? input.substring(5) : "";
                    if (str.equals("echo")
                            || str.equals("exit")
                            || str.equals("pwd")
                            || str.equals("type")
                            || str.equals("history")) {
                        System.out.println(str + " is a shell builtin");
                    } else {
                        System.out.println(find(str));
                    }
                } else if (str1.equals("echo")) {
                    System.out.println(input.substring(5));
                } else if (str1.equals("exit")) {
                    if (System.getenv().containsKey("HISTFILE")) {
                        StringBuilder write = new StringBuilder();
                        for (String command : commands) {
                            write.append(command + "\n");
                        }
                        Files.writeString(
                                getPath(System.getenv("HISTFILE")),
                                write.toString(),
                                StandardCharsets.UTF_8);
                    }
                    System.exit(0);
                } else {
                    System.out.println(input + ": not found");
                }
                System.out.print("$ ");
            }
        }
    }

    static void usePipe(String input, Builtins sharedBuiltins) throws Exception {
        List<List<String>> segments = splitPipeline(input);
        List<Proc> processList = new ArrayList<>();

        for (List<String> seg : segments) {
            if (seg.isEmpty()) continue;
            String cmd = seg.get(0);
            if (cmd.equals("echo") || cmd.equals("type") || cmd.equals("cd") || cmd.equals("pwd")) {
                processList.add(new BuiltinProc(cmd, seg.subList(1, seg.size()), sharedBuiltins));
            } else {
                // Pass full argv to external
                processList.add(
                        new ExternalProc(
                                seg, new File(System.getProperty("user.dir")), System.getenv()));
            }
        }
        startPipe(processList);
    }

    static int startPipe(List<Proc> ps) throws Exception {
        if (ps.isEmpty()) return 0;

        for (Proc p : ps) p.start();

        List<Thread> pumps = new ArrayList<>();
        for (int i = 0; i < ps.size() - 1; i++) {
            pumps.add(pump(ps.get(i).stdout(), ps.get(i + 1).stdin(), true));
        }
        Thread lastOut = pump(ps.get(ps.size() - 1).stdout(), System.out, false);

        List<Thread> errPumps = new ArrayList<>();
        for (Proc p : ps) errPumps.add(pump(p.stderr(), System.err, false));

        closeQuietly(ps.get(0).stdin());

        int code = 0;
        for (Proc p : ps) code = p.waitFor();

        for (Thread t : pumps) t.join();
        lastOut.join();
        for (Thread t : errPumps) t.join();

        return code;
    }

    static Thread pump(InputStream in, OutputStream out, boolean closeDest) {
        Thread t =
                new Thread(
                        () -> {
                            final byte[] buf = new byte[8192];
                            try {
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                    out.flush();
                                }
                            } catch (IOException ignored) {
                            } finally {
                                if (closeDest) closeQuietly(out);
                                closeQuietly(in);
                            }
                        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    static List<List<String>> splitPipeline(String s) {
        ArrayList<List<String>> out = new ArrayList<>();
        ArrayList<String> cur = new ArrayList<>();
        StringBuilder tok = new StringBuilder();
        boolean inS = false, inD = false, esc = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                tok.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '\'' && !inD) {
                inS = !inS;
                continue;
            }
            if (c == '"' && !inS) {
                inD = !inD;
                continue;
            }

            if (c == '|' && !inS && !inD) {
                pushTok(cur, tok);
                if (!cur.isEmpty()) out.add(new ArrayList<>(cur));
                cur.clear();
                continue;
            }
            if (Character.isWhitespace(c) && !inS && !inD) {
                pushTok(cur, tok);
                continue;
            }
            tok.append(c);
        }
        pushTok(cur, tok);
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    static void pushTok(List<String> cur, StringBuilder tok) {
        if (tok.length() > 0) {
            cur.add(tok.toString());
            tok.setLength(0);
        }
    }

    static void addToTrie(Trie trie) {
        String path = System.getenv("PATH");
        if (path == null) return;
        for (String dir : path.split(File.pathSeparator)) {
            File d = new File(dir);
            if (!d.isDirectory()) continue;
            File[] matches = d.listFiles(f -> f.isFile() && f.canExecute());
            if (matches == null || matches.length == 0) continue;
            for (File file : matches) trie.insert(file.getName());
        }
    }

    static void redirect(String input, boolean error, boolean append)
            throws IOException, InterruptedException {
        String[] inputs = input.split(" ");
        if (inputs[0].equals("echo")) {
            Deque<String> dq = new ArrayDeque<>();
            String dest = "";
            for (int i = 0; i < input.length(); ) {
                if (input.charAt(i) == '\'') {
                    int j = i;
                    StringBuilder sb = new StringBuilder();
                    j++;
                    while (j < input.length()) {
                        if (input.charAt(j) == '\'') {
                            i = j + 1;
                            break;
                        }
                        sb.append(input.charAt(j));
                        j++;
                    }
                    dq.offerLast(sb.toString());
                } else if (input.charAt(i) == '\"') {
                    int j = i;
                    StringBuilder sb = new StringBuilder();
                    j++;
                    while (j < input.length()) {
                        if (input.charAt(j) == '\"') {
                            i = j + 1;
                            break;
                        }
                        sb.append(input.charAt(j));
                        j++;
                    }
                    dq.offerLast(sb.toString());
                } else if (i + 1 < input.length()
                        && input.charAt(i) == '1'
                        && input.charAt(i + 1) == '>') {
                    dest = input.substring(i + 3);
                    break;
                } else if (input.charAt(i) == '>') {
                    dest = input.substring(i + 2);
                    break;
                } else {
                    i++;
                }
            }
            dest = dest.trim();
            if ((dest.startsWith("\"") && dest.endsWith("\""))
                    || (dest.startsWith("'") && dest.endsWith("'"))) {
                dest = dest.substring(1, dest.length() - 1);
            }
            StringBuilder outBuf = new StringBuilder();
            for (var it = dq.iterator(); it.hasNext(); ) {
                outBuf.append(it.next());
                if (it.hasNext()) outBuf.append(' ');
            }
            Path outPath = Path.of(dest);
            Path parent = outPath.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            if (error) {
                System.out.println(outBuf.toString());
                if (append) {
                    Files.write(
                            outPath,
                            "".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                } else {
                    Files.write(
                            outPath,
                            "".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
            } else {
                outBuf.append('\n');
                if (append) {
                    Files.write(
                            outPath,
                            outBuf.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                } else {
                    Files.write(
                            outPath,
                            outBuf.toString().getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
        } else if (inputs[0].equals("ls")) {
            int gt = input.lastIndexOf('>');
            if (gt != -1) {
                String lhs = input.substring(0, gt).trim();
                String right = input.substring(gt + 1).trim();
                String dest = unquote(firstToken(right));
                java.nio.file.Path out = java.nio.file.Path.of(dest);
                java.nio.file.Path parent = out.getParent();
                if (parent != null && !java.nio.file.Files.exists(parent)) {
                    java.nio.file.Files.createDirectories(parent);
                }
                String[] argv = lhs.split("\\s+");
                String exe = resolveOnPath(argv[0]);
                if (exe == null) {
                    System.out.println(argv[0] + ": not found");
                } else {
                    argv[0] = exe;
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new File(System.getProperty("user.dir")));
                    if (error) {
                        if (append)
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile()));
                        else pb.redirectError(ProcessBuilder.Redirect.to(out.toFile()));
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    } else {
                        if (append)
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
                        else pb.redirectOutput(out.toFile());
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    Process p = pb.start();
                    p.waitFor();
                }
            } else {
                ProcessBuilder pb = new ProcessBuilder(input.trim().split("\\s+"));
                pb.directory(new java.io.File(System.getProperty("user.dir")));
                pb.inheritIO();
                Process p = pb.start();
                p.waitFor();
            }
        } else if (inputs[0].equals("cat")) {
            int gt = input.lastIndexOf('>');
            if (gt == -1) return;
            int j = gt - 1;
            while (j >= 0 && Character.isWhitespace(input.charAt(j))) j--;
            boolean oneRedir =
                    (j >= 0
                            && input.charAt(j) == '1'
                            && !(j - 1 >= 0 && input.charAt(j - 1) == '-'));
            String lhs = input.substring(0, oneRedir ? j : gt).trim();
            String rhs = input.substring(gt + 1).trim();
            String dest = unquote(firstToken(rhs));
            Path out = Path.of(dest);
            Path parent = out.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            String srcPart = lhs.startsWith("cat") ? lhs.substring(3).trim() : lhs;
            List<String> sources = tokenizeArgs(srcPart);
            if (sources.isEmpty()) {
                Files.write(
                        out,
                        new byte[0],
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                return;
            }
            List<String> argv = new ArrayList<>();
            String catExe = resolveOnPath("cat");
            argv.add(catExe != null ? catExe : "cat");
            argv.addAll(sources);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(new File(System.getProperty("user.dir")));
            if (error) {
                if (append) pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile()));
                else pb.redirectError(ProcessBuilder.Redirect.to(out.toFile()));
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            } else {
                if (append) pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
                else pb.redirectOutput(out.toFile());
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            p.waitFor();
        }
    }

    static String firstToken(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    static String unquote(String s) {
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static List<String> fileOnTab(String str) {
        List<String> files = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) return files;
        for (String dir : path.split(File.pathSeparator)) {
            File d = new File(dir);
            if (!d.isDirectory()) continue;
            File[] matches =
                    d.listFiles(f -> f.isFile() && f.getName().startsWith(str) && f.canExecute());
            if (matches == null || matches.length == 0) continue;
            for (File file : matches) files.add(file.getName());
        }
        Collections.sort(files);
        return files;
    }

    static Path getPath(String name) {
        if (name.contains(java.io.File.separator)) return new java.io.File(name).toPath();
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, name);
            if (f.isFile() && f.canExecute()) return f.toPath();
        }
        return new java.io.File(name).toPath();
    }

    static String resolveOnPath(String name) {
        if (name.contains(java.io.File.separator)) return new java.io.File(name).getAbsolutePath();
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, name);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    static String[] convert(String input) {
        Deque<String> response = new ArrayDeque<>();
        int ind = 0;
        while (ind < input.length()) {
            if (input.charAt(ind) == '/') {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && (input.charAt(ind) != ' ')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\'') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && (input.charAt(ind) != '\'')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\"') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && (input.charAt(ind) != '\"')) {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            }
            ind++;
        }
        return response.toArray(new String[response.size()]);
    }

    static void content(String file) {
        Process p;
        try {
            p = new ProcessBuilder("cat", file).redirectErrorStream(true).start();
            p.getInputStream().transferTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static String print(String input) {
        Deque<String> dq = new ArrayDeque<>();
        int ind = 0;
        while (ind < input.length()) {
            if (input.charAt(ind) == '"') {
                StringBuilder sb = new StringBuilder();
                ind++;
                while (ind != input.length() && input.charAt(ind) != '"') {
                    if (input.charAt(ind) == '\\' && ind + 1 < input.length()) {
                        sb.append(input.charAt(ind + 1));
                        ind += 2;
                        continue;
                    }
                    sb.append(input.charAt(ind));
                    ind++;
                }
                ind++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\'') {
                StringBuilder sb = new StringBuilder();
                ind++;
                while (ind != input.length() && input.charAt(ind) != '\'') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                ind++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\\') {
                dq.offerLast(new StringBuilder("").append(input.charAt(ind + 1)).toString());
                ind += 2;
                continue;
            } else if (input.charAt(ind) == ' ') {
                if (dq.size() > 0 && dq.peekLast().equals(" ")) {
                    ind++;
                    continue;
                }
                dq.offerLast(" ");
                ind++;
            } else {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length()
                        && input.charAt(ind) != ' '
                        && input.charAt(ind) != '\''
                        && input.charAt(ind) != '"'
                        && input.charAt(ind) != '\\') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offerLast(sb.toString());
            }
        }
        StringBuilder sb = new StringBuilder();
        while (dq.size() > 0) {
            sb.append(dq.peekFirst());
            dq.pollFirst();
        }
        return sb.toString();
    }

    static List<String> tokenizeArgs(String s) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inS = false, inD = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '\'' && !inD) {
                inS = !inS;
                continue;
            }
            if (c == '"' && !inS) {
                inD = !inD;
                continue;
            }
            if (Character.isWhitespace(c) && !inS && !inD) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static void change(String input) {
        String path = System.getProperty("user.dir");
        Deque<String> dq = new ArrayDeque<>();
        String[] paths = path.split("/");
        for (int i = 0; i < paths.length; i++) dq.offer(paths[i]);
        dq.pollFirst();
        int ind = 0;
        while (ind < input.length()) {
            if (ind + 2 < input.length() && input.substring(ind, ind + 3).equals("../")) {
                dq.pollLast();
                ind += 3;
                continue;
            } else if (input.charAt(ind) == '/') {
                ind++;
                StringBuilder sb = new StringBuilder("");
                while (ind < input.length() && input.charAt(ind) != '/') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offer(sb.toString());
            } else {
                ind++;
            }
        }
        StringBuilder sb = new StringBuilder("");
        while (dq.size() > 0) {
            sb.append('/');
            sb.append(dq.peekFirst());
            dq.pollFirst();
        }
        System.setProperty("user.dir", sb.toString());
    }

    public static String exists(String input) {
        Path path = Path.of(input);
        File file = path.toFile();
        if (file.exists() && file.isDirectory()) return file.getAbsolutePath();
        return null;
    }

    static boolean check(String input) {
        List<String> toks = tokenizeArgs(input);
        if (toks.isEmpty()) return false;
        String cmd = toks.get(0);
        String path = System.getenv("PATH");
        if (path == null) return false;
        String[] commands = path.split(File.pathSeparator);
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], cmd);
            if (file.exists() && file.canExecute()) return true;
        }
        return false;
    }

    static String find(String str) {
        String path = System.getenv("PATH");
        if (path == null) return str + ": not found";
        String[] commands = path.split(File.pathSeparator);
        String ans = str + ": not found";
        for (int i = 0; i < commands.length; i++) {
            File file = new File(commands[i], str);
            if (file.exists() && file.canExecute()) return str + " is " + file.getAbsolutePath();
        }
        return ans;
    }
}