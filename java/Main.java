import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    enum ShellType {
        TYPE, ECHO, EXIT, PWD, CD, CAT, NONE
    }

    private static final Map<String, ShellType> builtins =
            Map.of(
                    "type", ShellType.TYPE,
                    "echo", ShellType.ECHO,
                    "exit", ShellType.EXIT,
                    "pwd", ShellType.PWD,
                    "cd", ShellType.CD);
    
    private static final Map<String, ShellType> externals = Map.of("cat", ShellType.CAT);

    private static final String PATH = System.getenv("PATH");
    private static final String[] DIRECTORIES = PATH != null ? PATH.split(File.pathSeparator) : new String[0];
    private static File currentDir = new File(System.getProperty("user.dir"));

    public static void main(String[] args) throws Exception {
        boolean exit = false;
        Scanner scanner = new Scanner(System.in);
        
        while (!exit) {
            System.out.print("$ ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            // Tokenize input, handling single and double quotes
            // Tokenize input, handling single quotes
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
                    default -> nullCommand(parts);
                }
            }
        }
        scanner.close();
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
                    default -> nullCommand(parts);
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
        scanner.close();
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
            case PWD -> {
                out.println(currentDir.getAbsolutePath());
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

    // Parse input with single and double quote support
    // Parse input with single-quote support
    private static List<String> parseInput(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escape = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (c == '\"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                continue; // skip the quote itself
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
                // Normalize the path to resolve . and .. components
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
}

// git commit -m "Implement single-quote support for echo and arguments"
// git add .
// git commit -m "Add pipeline support for shell commands"
// git push origin master
// git commit -m "Fix shell parsing and implement builtins with correct quote/escape handling"
// git push origin master 
