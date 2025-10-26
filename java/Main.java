import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // Define all shell builtins
        Set<String> builtins = new HashSet<>();
        builtins.add("echo");
        builtins.add("exit");
        builtins.add("type");

        while (true) {
            // Display prompt
            System.out.print("$ ");

            // Read input
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            // Split input into parts
            String[] parts = input.split("\\s+");
            String command = parts[0];

            // Handle 'exit'
            if (command.equals("exit")) {
                int exitCode = 0;
                if (parts.length > 1) {
                    try {
                        exitCode = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        exitCode = 0;
                    }
                }
                System.exit(exitCode);
            }

            // Handle 'echo'
            else if (command.equals("echo")) {
                if (parts.length > 1) {
                    String output = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length));
                    System.out.println(output);
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
                        System.out.println(cmdToCheck + ": not found");
                    }
                } else {
                    // No argument provided
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
