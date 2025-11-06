import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        File currentDir = new File(System.getProperty("user.dir"));

        Set<String> builtins = new HashSet<>();
        builtins.add("echo");
        builtins.add("exit");
        builtins.add("type");
        builtins.add("pwd");
        builtins.add("cd");

        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            // Tokenize input, handling single and double quotes
            List<String> parts = parseInput(input);
            if (parts.isEmpty()) continue;

            String command = parts.get(0);

            // exit
            if (command.equals("exit")) {
                int exitCode = 0;
                if (parts.size() > 1) {
                    try { exitCode = Integer.parseInt(parts.get(1)); } 
                    catch (NumberFormatException e) { exitCode = 0; }
                }
                System.exit(exitCode);
            }

            // echo
            else if (command.equals("echo")) {
                if (parts.size() > 1) {
                    System.out.println(String.join(" ", parts.subList(1, parts.size())));
                } else {
                    System.out.println();
                }
            }

            // type
            else if (command.equals("type")) {
                if (parts.size() > 1) {
                    String cmdToCheck = parts.get(1);
                    if (builtins.contains(cmdToCheck)) {
                        System.out.println(cmdToCheck + " is a shell builtin");
                    } else {
                        String pathEnv = System.getenv("PATH");
                        boolean found = false;
                        if (pathEnv != null && !pathEnv.isEmpty()) {
                            String[] dirs = pathEnv.split(File.pathSeparator);
                            for (String dir : dirs) {
                                File file = new File(dir, cmdToCheck);
                                if (file.exists() && file.canExecute()) {
                                    System.out.println(cmdToCheck + " is " + file.getAbsolutePath());
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (!found) {
                            System.out.println(cmdToCheck + ": not found");
                        }
                    }
                } else {
                    System.out.println("type: missing argument");
                }
            }

            // pwd
            else if (command.equals("pwd")) {
                System.out.println(currentDir.getAbsolutePath());
            }

            // cd
            else if (command.equals("cd")) {
                if (parts.size() > 1) {
                    String target = parts.get(1);
                    File targetDir;

                    if (target.equals("~")) {
                        String home = System.getenv("HOME");
                        if (home != null && !home.isEmpty()) {
                            targetDir = new File(home);
                        } else {
                            System.out.println("cd: HOME not set");
                            continue;
                        }
                    } else {
                        targetDir = new File(target);
                        if (!targetDir.isAbsolute()) {
                            targetDir = new File(currentDir, target);
                        }
                    }

                    if (targetDir.exists() && targetDir.isDirectory()) {
                        try {
                            currentDir = targetDir.getCanonicalFile();
                            System.setProperty("user.dir", currentDir.getAbsolutePath());
                        } catch (IOException e) {
                            System.out.println("cd: " + target + ": Error resolving path");
                        }
                    } else {
                        System.out.println("cd: " + target + ": No such file or directory");
                    }

                } else {
                    System.out.println("cd: missing argument");
                }
            }

            // external programs
            else {
                String pathEnv = System.getenv("PATH");
                boolean found = false;
                if (pathEnv != null && !pathEnv.isEmpty()) {
                    String[] dirs = pathEnv.split(File.pathSeparator);
                    for (String dir : dirs) {
                        File file = new File(dir, command);
                        if (file.exists() && file.canExecute()) {
                            ProcessBuilder pb = new ProcessBuilder(parts);
                            pb.directory(currentDir);
                            pb.inheritIO();
                            try {
                                Process p = pb.start();
                                p.waitFor();
                            } catch (IOException | InterruptedException e) {
                                System.out.println("Error executing command: " + e.getMessage());
                            }
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    // Parse input with single and double quote support
    private static List<String> parseInput(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

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
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }
}

// git add .
// git commit -m "Implement double-quote support for echo and arguments"
// git push origin master