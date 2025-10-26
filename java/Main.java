import java.util.Scanner;
import java.util.Set;
import java.util.HashSet;
import java.io.File;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // Define builtins
        Set<String> builtins = new HashSet<>();
        builtins.add("echo");
        builtins.add("exit");
        builtins.add("type");

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

                    // Check builtins first
                    if (builtins.contains(cmdToCheck)) {
                        System.out.println(cmdToCheck + " is a shell builtin");
                    } else {
                        // Search PATH
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

            // Unrecognized command
            else {
                System.out.println(input + ": command not found");
            }
        }
    }
}