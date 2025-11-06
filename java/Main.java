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

// git add .
// git commit -m "Fix shell parsing and implement builtins with correct quote/escape handling"
// git push origin master 