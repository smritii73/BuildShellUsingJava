import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // Define builtins
        Set<String> builtins = new HashSet<>();
        builtins.add("echo");
        builtins.add("exit");
        builtins.add("type");
        builtins.add("pwd");

        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String command = parts[0];

            // Handle 'exit'
            if (command.equals("exit")) {
                int exitCode = 0;
                if (parts.length > 1) {
                    try { exitCode = Integer.parseInt(parts[1]); } 
                    catch (NumberFormatException e) { exitCode = 0; }
                }
                System.exit(exitCode);
            }

            // Handle 'echo'
            else if (command.equals("echo")) {
                if (parts.length > 1) {
                    System.out.println(String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
                } else {
                    System.out.println();
                }
            }

            // Handle 'type'
            else if (command.equals("type")) {
                if (parts.length > 1) {
                    String cmdToCheck = parts[1];
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
            else if (command.equals("pwd")) {
    System.out.println(System.getProperty("user.dir"));
}
            // Handle external programs
            else {
                String pathEnv = System.getenv("PATH");
                boolean found = false;
                if (pathEnv != null && !pathEnv.isEmpty()) {
                    String[] dirs = pathEnv.split(File.pathSeparator);
                    for (String dir : dirs) {
                        File file = new File(dir, command);
                        if (file.exists() && file.canExecute()) {
                            // Build argument list for ProcessBuilder
                            String[] cmdWithArgs = parts; // parts already includes program + args
                            ProcessBuilder pb = new ProcessBuilder(cmdWithArgs);
                            pb.inheritIO(); // Redirect subprocess stdout/stderr to our shell
                            try {
                                Process p = pb.start();
                                p.waitFor(); // Wait for process to finish
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
}
