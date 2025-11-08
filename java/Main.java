import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    enum ShellType {
        TYPE, ECHO, EXIT, PWD, CD, CAT, HISTORY, NONE
    }

    private static final Map<String, ShellType> builtins =
            Map.of(
                    "type", ShellType.TYPE,
                    "echo", ShellType.ECHO,
                    "exit", ShellType.EXIT,
                    "pwd", ShellType.PWD,
                    "cd", ShellType.CD,
                    "history", ShellType.HISTORY);
    
    private static final Map<String, ShellType> externals = Map.of("cat", ShellType.CAT);

    private static final String PATH = System.getenv("PATH");
    private static final String[] DIRECTORIES = PATH != null ? PATH.split(File.pathSeparator) : new String[0];
    private static File currentDir = new File(System.getProperty("user.dir"));
    private static final List<String> commandHistory = new ArrayList<>();
    private static final String HISTFILE = System.getenv("HISTFILE");
    private static int historyLoadedCount = 0; // Track how many commands were loaded from file
    private static final Map<String, Integer> fileAppendIndex = new HashMap<>(); // Track last appended index per file

    public static void main(String[] args) throws Exception {
        // Load history from file on startup
        loadHistoryFromFile();
        
        boolean exit = false;
        
        while (!exit) {
            System.out.print("$ ");
            System.out.flush();
            
            String input = readLineWithHistory();
            
            if (input == null) {
                break;
            }
            
            if (input.trim().isEmpty()) continue;
            
            input = input.trim();
            
            // Add command to our history list
            commandHistory.add(input);

            // Check for pipeline
            if (input.contains(" | ")) {
                executePipeline(input);
                continue;
            }

            List<String> parts = parseInput(input);
            if (parts.isEmpty()) continue;

            String command = parts.get(0);
            String[] arguments = parts.subList(1, parts.size()).toArray(new String[0]);

            if (externals.containsKey(command)) {
                switch (externals.getOrDefault(command, ShellType.NONE)) {
                    case CAT -> cat(arguments);
                    default -> nullCommand(parts);
                }
            } else {
                switch (builtins.getOrDefault(command, ShellType.NONE)) {
                    case EXIT -> exit = true;
                    case ECHO -> echo(arguments);
                    case TYPE -> type(arguments);
                    case PWD -> pwd();
                    case CD -> cd(arguments);
                    case HISTORY -> history(arguments);
                    default -> nullCommand(parts);
                }
            }
        }
        
        // Save history to file on exit
        saveHistoryToFile();
        
        System.out.println(); // Final newline before exit
    }

    private static void loadHistoryFromFile() {
        if (HISTFILE == null || HISTFILE.isEmpty()) {
            return;
        }
        
        File histFile = new File(HISTFILE);
        if (!histFile.exists()) {
            return;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(histFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Skip empty lines when loading
                if (!line.trim().isEmpty()) {
                    commandHistory.add(line);
                    historyLoadedCount++; // Track loaded commands
                }
            }
        } catch (IOException e) {
            // Silently ignore errors reading history file
        }
    }
    
    private static void saveHistoryToFile() {
        if (HISTFILE == null || HISTFILE.isEmpty()) {
            return;
        }
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(HISTFILE))) {
            for (String cmd : commandHistory) {
                writer.write(cmd);
                writer.newLine();
            }
        } catch (IOException e) {
            // Silently ignore errors writing history file
        }
    }

    private static String readLineWithHistory() throws IOException {
        // Try to enable raw mode for arrow key support
        boolean rawModeEnabled = false;
        try {
            String[] enableCmd = {"/bin/sh", "-c", "stty raw -echo < /dev/tty"};
            Process p = Runtime.getRuntime().exec(enableCmd);
            p.waitFor();
            rawModeEnabled = (p.exitValue() == 0);
        } catch (Exception e) {
            // Couldn't enable raw mode
        }
        
        if (!rawModeEnabled) {
            // Fall back to simple readline
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            return reader.readLine();
        }
        
        // Raw mode enabled successfully, handle input with arrow keys
        try {
            StringBuilder line = new StringBuilder();
            int historyIndex = commandHistory.size();
            String savedLine = "";
            
            while (true) {
                int c = System.in.read();
                
                if (c == -1) {
                    return null;
                }
                
                // Handle newline (Enter key)
                if (c == '\n' || c == '\r') {
                    System.out.print("\r\n");
                    System.out.flush();
                    return line.toString();
                }
                
                // Handle backspace
                if (c == 127 || c == 8) {
                    if (line.length() > 0) {
                        line.deleteCharAt(line.length() - 1);
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                    continue;
                }
                
                // Handle escape sequences (arrow keys)
                if (c == 27) { // ESC
                    int next1 = System.in.read();
                    if (next1 == '[') {
                        int next2 = System.in.read();
                        
                        if (next2 == 'A') { // Up arrow
                            if (historyIndex > 0) {
                                if (historyIndex == commandHistory.size()) {
                                    savedLine = line.toString();
                                }
                                historyIndex--;
                                clearLine(line.length());
                                line.setLength(0);
                                line.append(commandHistory.get(historyIndex));
                                System.out.print(line);
                                System.out.flush();
                            }
                        } else if (next2 == 'B') { // Down arrow
                            if (historyIndex < commandHistory.size()) {
                                historyIndex++;
                                clearLine(line.length());
                                line.setLength(0);
                                if (historyIndex == commandHistory.size()) {
                                    line.append(savedLine);
                                } else {
                                    line.append(commandHistory.get(historyIndex));
                                }
                                System.out.print(line);
                                System.out.flush();
                            }
                        }
                    }
                    continue;
                }
                
                // Handle Ctrl+C
                if (c == 3) {
                    System.out.print("^C\r\n");
                    System.out.flush();
                    return "";
                }
                
                // Handle Ctrl+D
                if (c == 4) {
                    if (line.length() == 0) {
                        return null;
                    }
                    continue;
                }
                
                // Regular character - add to buffer and echo it back
                // We echo because we used -echo flag in stty
                if (c >= 32 && c < 127) {
                    line.append((char) c);
                    System.out.print((char) c);
                    System.out.flush();
                }
            }
        } finally {
            // Always restore terminal mode
            try {
                String[] restoreCmd = {"/bin/sh", "-c", "stty sane < /dev/tty"};
                Runtime.getRuntime().exec(restoreCmd).waitFor();
            } catch (Exception e) {
                // Ignore restoration errors
            }
        }
    }
    
    private static void clearLine(int length) {
        for (int i = 0; i < length; i++) {
            System.out.print("\b \b");
        }
        System.out.flush();
    }

    private static void executePipeline(String input) throws IOException, InterruptedException {
        // Split by pipe operator
        String[] commands = input.split("\\s*\\|\\s*");
        
        List<List<String>> parsedCommands = new ArrayList<>();
        for (String cmd : commands) {
            List<String> parsed = parseInput(cmd.trim());
            if (!parsed.isEmpty()) {
                parsedCommands.add(parsed);
            }
        }
        
        if (parsedCommands.size() < 2) {
            System.out.println("Pipeline requires at least 2 commands");
            return;
        }
        
        // Build pipeline with mixed built-ins and external commands
        executeMixedPipeline(parsedCommands);
    }
    
    private static void executeMixedPipeline(List<List<String>> commands) throws IOException, InterruptedException {
        PipedOutputStream[] pipeOuts = new PipedOutputStream[commands.size() - 1];
        PipedInputStream[] pipeIns = new PipedInputStream[commands.size() - 1];
        
        // Create pipes between commands
        for (int i = 0; i < commands.size() - 1; i++) {
            pipeOuts[i] = new PipedOutputStream();
            pipeIns[i] = new PipedInputStream(pipeOuts[i], 65536);
        }
        
        List<Thread> threads = new ArrayList<>();
        List<Process> processes = new ArrayList<>();
        
        for (int i = 0; i < commands.size(); i++) {
            List<String> cmd = commands.get(i);
            String cmdName = cmd.get(0);
            String[] args = cmd.subList(1, cmd.size()).toArray(new String[0]);
            
            InputStream cmdInput;
            OutputStream cmdOutput;
            
            // Set input stream
            if (i == 0) {
                cmdInput = System.in;
            } else {
                cmdInput = pipeIns[i - 1];
            }
            
            // Set output stream
            if (i == commands.size() - 1) {
                cmdOutput = System.out;
            } else {
                cmdOutput = pipeOuts[i];
            }
            
            // Check if it's a built-in command
            if (builtins.containsKey(cmdName)) {
                final InputStream finalInput = cmdInput;
                final OutputStream finalOutput = cmdOutput;
                final int index = i;
                
                Thread builtinThread = new Thread(() -> {
                    try {
                        executeBuiltinInPipeline(cmdName, args, finalInput, finalOutput);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        // Close output stream if it's a pipe (not stdout)
                        if (index < commands.size() - 1) {
                            closeQuietly(finalOutput);
                        }
                    }
                });
                builtinThread.start();
                threads.add(builtinThread);
            } else {
                // External command
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(currentDir);
                
                Process process = pb.start();
                processes.add(process);
                
                // Connect input
                if (i > 0) {
                    final InputStream in = cmdInput;
                    final OutputStream out = process.getOutputStream();
                    Thread inputThread = new Thread(() -> {
                        pipeData(in, out, true);
                    });
                    inputThread.start();
                    threads.add(inputThread);
                } else {
                    closeQuietly(process.getOutputStream());
                }
                
                // Connect output
                if (i < commands.size() - 1) {
                    final InputStream in = process.getInputStream();
                    final OutputStream out = cmdOutput;
                    Thread outputThread = new Thread(() -> {
                        pipeData(in, out, true);
                    });
                    outputThread.start();
                    threads.add(outputThread);
                } else {
                    Thread outputThread = new Thread(() -> {
                        pipeData(process.getInputStream(), System.out, false);
                    });
                    outputThread.start();
                    threads.add(outputThread);
                }
                
                // Always pipe stderr to System.err
                Thread errorThread = new Thread(() -> {
                    pipeData(process.getErrorStream(), System.err, false);
                });
                errorThread.start();
                threads.add(errorThread);
            }
        }
        
        // Wait for all threads to complete
        for (Thread t : threads) {
            t.join();
        }
        
        // Wait for all processes to complete
        for (Process p : processes) {
            p.waitFor();
        }
    }
    
    private static void executeBuiltinInPipeline(String cmdName, String[] args, 
                                                  InputStream input, OutputStream output) throws IOException {
        PrintStream out = new PrintStream(output, true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        
        switch (builtins.get(cmdName)) {
            case ECHO -> {
                if (args.length > 0) {
                    out.println(String.join(" ", args));
                } else {
                    out.println();
                }
            }
            case TYPE -> {
                // Read and discard input from pipeline
                while (reader.ready() && reader.readLine() != null) {
                    // Consume input but don't use it
                }
                
                if (args.length >= 1) {
                    String cmdToCheck = args[0];
                    if (builtins.containsKey(cmdToCheck)) {
                        out.println(cmdToCheck + " is a shell builtin");
                    } else {
                        boolean found = false;
                        for (String dir : DIRECTORIES) {
                            File file = new File(dir, cmdToCheck);
                            if (file.exists() && file.canExecute()) {
                                out.println(cmdToCheck + " is " + file.getAbsolutePath());
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            out.println(cmdToCheck + ": not found");
                        }
                    }
                }
            }
            case PWD -> {
                out.println(currentDir.getAbsolutePath());
            }
            case HISTORY -> {
                // Check if -a flag is present (append)
                if (args.length >= 2 && args[0].equals("-a")) {
                    // -a flag doesn't make sense in pipeline, ignore it
                    return;
                }
                
                // Check if -w flag is present (write)
                if (args.length >= 2 && args[0].equals("-w")) {
                    // -w flag doesn't make sense in pipeline, ignore it
                    return;
                }
                
                // Check if -r flag is present (read)
                if (args.length >= 2 && args[0].equals("-r")) {
                    // -r flag doesn't make sense in pipeline, ignore it
                    return;
                }
                
                int limit = commandHistory.size();
                if (args.length > 0) {
                    try {
                        limit = Integer.parseInt(args[0]);
                    } catch (NumberFormatException e) {
                        // Use default (all history)
                    }
                }
                int start = Math.max(0, commandHistory.size() - limit);
                for (int i = start; i < commandHistory.size(); i++) {
                    out.printf("%5d  %s%n", i + 1, commandHistory.get(i));
                }
            }
            case CAT -> {
                // If no args, read from stdin
                if (args.length == 0) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.println(line);
                    }
                } else {
                    for (String file : args) {
                        try {
                            Path filePath = Paths.get(file);
                            String content = Files.readString(filePath);
                            out.print(content);
                        } catch (IOException e) {
                            System.err.println("cat: " + file + ": No such file or directory");
                        }
                    }
                }
            }
            default -> {
                // Other built-ins that don't make sense in pipelines
            }
        }
        
        out.flush();
    }
    
    private static void pipeData(InputStream in, OutputStream out, boolean closeOut) {
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // Stream closed or error
        } finally {
            closeQuietly(in);
            if (closeOut) {
                closeQuietly(out);
            }
        }
    }
    
    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static List<String> parseInput(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escape = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (escape) {
                if (inSingle) {
                    current.append('\\').append(c);
                } else if (inDouble) {
                    switch (c) {
                        case '$', '`', '"', '\\', '\n' -> current.append(c);
                        default -> current.append('\\').append(c);
                    }
                } else {
                    current.append(c);
                }
                escape = false;
                continue;
            }

            if (c == '\\') {
                escape = true;
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(c);
        }

        if (escape) {
            current.append('\\');
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    private static String typeText(String command) {
        return command + " is a shell builtin";
    }

    private static String notFoundText(String command) {
        return command + ": not found";
    }

    private static String commandNotFoundText(String command) {
        return command + ": command not found";
    }

    private static void type(String[] command) {
        if (command.length >= 1) {
            String cmdToCheck = command[0];
            if (builtins.containsKey(cmdToCheck)) {
                System.out.println(typeText(cmdToCheck));
            } else {
                boolean found = false;
                for (String dir : DIRECTORIES) {
                    File file = new File(dir, cmdToCheck);
                    if (file.exists() && file.canExecute()) {
                        System.out.println(cmdToCheck + " is " + file.getAbsolutePath());
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    System.out.println(notFoundText(cmdToCheck));
                }
            }
        } else {
            System.out.println("type: missing argument");
        }
    }

    private static void nullCommand(List<String> parts) throws IOException {
        String command = parts.get(0);
        for (String dir : DIRECTORIES) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) {
                ProcessBuilder pb = new ProcessBuilder(parts);
                pb.directory(currentDir);
                pb.inheritIO();
                try {
                    Process program = pb.start();
                    program.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        }
        System.out.println(commandNotFoundText(command));
    }

    private static void pwd() {
        System.out.println(currentDir.getAbsolutePath());
    }

    private static void cd(String[] command) {
        if (command.length >= 1) {
            String target = command[0];
            File targetDir;
            
            if (target.equals("~")) {
                String home = System.getenv("HOME");
                targetDir = (home != null) ? new File(home) : new File(System.getProperty("user.home"));
            } else if (new File(target).isAbsolute()) {
                targetDir = new File(target);
            } else {
                targetDir = new File(currentDir, target);
            }
            
            try {
                Path normalizedPath = targetDir.toPath().toRealPath();
                targetDir = normalizedPath.toFile();
            } catch (IOException e) {
                // If we can't normalize (path doesn't exist), use the original
            }
            
            if (targetDir.exists() && targetDir.isDirectory()) {
                currentDir = targetDir;
                System.setProperty("user.dir", currentDir.getAbsolutePath());
            } else {
                System.out.println("cd: " + target + ": No such file or directory");
            }
        } else {
            System.out.println("cd: missing argument");
        }
    }

    private static void echo(String[] output) {
        if (output.length > 0) {
            System.out.println(String.join(" ", output));
        } else {
            System.out.println();
        }
    }

    private static void cat(String[] files) {
        for (String file : files) {
            try {
                Path filePath = Paths.get(file);
                String content = Files.readString(filePath);
                System.out.print(content);
            } catch (IOException e) {
                System.out.println("cat: " + file + ": No such file or directory");
            }
        }
    }

    private static void history(String[] args) {
        // Check if -a flag is present (append)
        if (args.length >= 2 && args[0].equals("-a")) {
            String filename = args[1];
            appendHistoryToFile(filename);
            return;
        }
        
        // Check if -w flag is present (write/overwrite)
        if (args.length >= 2 && args[0].equals("-w")) {
            String filename = args[1];
            writeHistoryToFile(filename);
            return;
        }
        
        // Check if -r flag is present (read/load)
        if (args.length >= 2 && args[0].equals("-r")) {
            String filename = args[1];
            readHistoryFromFile(filename);
            return;
        }
        
        int limit = commandHistory.size();
        if (args.length > 0) {
            try {
                limit = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                // Use default (all history)
            }
        }
        int start = Math.max(0, commandHistory.size() - limit);
        for (int i = start; i < commandHistory.size(); i++) {
            System.out.printf("%5d  %s%n", i + 1, commandHistory.get(i));
        }
    }
    
    private static void readHistoryFromFile(String filename) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    commandHistory.add(line);
                    historyLoadedCount++; // Track as loaded
                }
            }
        } catch (IOException e) {
            System.err.println("history: " + filename + ": cannot read file");
        }
    }
    
    private static void writeHistoryToFile(String filename) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, false))) {
            for (String cmd : commandHistory) {
                writer.write(cmd);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("history: " + filename + ": cannot write to file");
        }
    }
    
    private static void appendHistoryToFile(String filename) {
        try {
            // Determine the starting index for this file
            // If we've appended to this file before, start from where we left off
            // Otherwise, start from the commands entered in this session (after loaded history)
            int startIndex = fileAppendIndex.getOrDefault(filename, historyLoadedCount);
            
            // Append only new commands from this session to the file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
                for (int i = startIndex; i < commandHistory.size(); i++) {
                    writer.write(commandHistory.get(i));
                    writer.newLine();
                }
            }
            
            // Update the tracking for this file to the current history size
            fileAppendIndex.put(filename, commandHistory.size());
        } catch (IOException e) {
            System.err.println("history: " + filename + ": cannot write to file");
        }
    }
}

// git add .
// git commit -m "Added Histfile environment variable"
// git push origin master