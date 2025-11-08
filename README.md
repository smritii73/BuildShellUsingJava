# BuildShellUsingJava 

A feature-rich custom shell implementation in Java that provides Unix-like shell functionality with support for built-in commands, pipelines, command history, and more.

## Features ✨

### Built-in Commands
- **`exit`** - Exit the shell
- **`echo [text]`** - Print text to standard output
- **`pwd`** - Print current working directory
- **`cd [directory]`** - Change directory (supports `~`, relative and absolute paths)
- **`type [command]`** - Check if a command is a builtin or show its executable path
- **`cat [files...]`** - Display contents of one or more files
- **`history [n]`** - Display command history

### Advanced Features
- **🔗 Pipeline Support** - Chain commands using `|` operator
- **📝 Persistent History** - Automatic history save/load using `HISTFILE` environment variable
- **⬆️⬇️ Arrow Key Navigation** - Browse command history (Unix/Linux/Mac)
- **🎯 Quote Handling** - Support for single quotes, double quotes, and backslash escaping
- **🚀 External Command Execution** - Run any program available in your system's PATH
- **💾 History Management** - Read, write, and append history to custom files

## Prerequisites 📋

- Java Development Kit (JDK) 8 or higher
- Java compiler (`javac`)

## Installation & Setup 🛠️

1. **Clone the repository**
   ```bash
   git clone https://github.com/smritii73/BuildShellUsingJava.git
   cd BuildShellUsingJava
   ```

2. **Compile the code**
   ```bash
   javac Main.java
   ```

3. **Run the shell**
   ```bash
   java Main
   ```

## Usage Examples 💡

### Basic Commands
```bash
$ pwd
/home/user/projects

$ echo Hello World
Hello World

$ cd ~
$ pwd
/home/user
```

### Pipelines
Chain multiple commands together:
```bash
$ echo "Hello World" | cat
Hello World

$ pwd | cat
/current/directory
```

### Command History
```bash
# View all history
$ history

# View last 5 commands
$ history 5

# Save history to file
$ history -w commands.txt

# Append new commands to file
$ history -a session_log.txt

# Load history from file
$ history -r previous_commands.txt
```

### File Operations
```bash
$ cat file1.txt
Contents of file1

$ cat file1.txt file2.txt file3.txt
Contents of file1
Contents of file2
Contents of file3
```

### Quote Handling
```bash
$ echo 'single quotes preserve everything'
single quotes preserve everything

$ echo "double quotes allow \"escapes\""
double quotes allow "escapes"

$ echo backslash\ works
backslash works
```

## History Management 📚

### Persistent History with HISTFILE

Set the `HISTFILE` environment variable before starting the shell:

**Unix/Linux/Mac:**
```bash
export HISTFILE=~/.myshell_history
java Main
```

**Windows (PowerShell):**
```powershell
$env:HISTFILE="$HOME\.myshell_history"
java Main
```

The shell will automatically:
- Load history from `HISTFILE` on startup
- Save history to `HISTFILE` on exit

### History Commands

| Command | Description |
|---------|-------------|
| `history` | Display all commands with line numbers |
| `history N` | Display last N commands |
| `history -r file` | Read/load history from file |
| `history -w file` | Write all history to file (overwrite) |
| `history -a file` | Append new session commands to file |

## Keyboard Shortcuts ⌨️

| Shortcut | Action |
|----------|--------|
| `↑` (Up Arrow) | Previous command in history |
| `↓` (Down Arrow) | Next command in history |
| `Backspace` | Delete character |
| `Ctrl+C` | Cancel current line |
| `Ctrl+D` | Exit shell (when line is empty) |
| `Enter` | Execute command |

> **Note:** Arrow key navigation works on Unix/Linux/Mac systems. On Windows, it falls back to simple line reading.

## Technical Details 🔧

### Architecture
- **Built-in Commands**: Implemented directly in Java
- **External Commands**: Executed via `ProcessBuilder`
- **Pipeline Execution**: Mixed threading model supporting both built-ins and external commands
- **Input Parsing**: Custom parser handling quotes and escapes

### Quote and Escape Rules
- **Single quotes (`'`)**: Preserve all characters literally
- **Double quotes (`"`)**: Allow escape sequences for `$`, `` ` ``, `"`, `\`, and newline
- **Backslash (`\`)**: Escape the next character

### Pipeline Implementation
- Supports multiple commands in a single pipeline
- Uses `PipedInputStream` and `PipedOutputStream` for inter-command communication
- Threads handle concurrent execution of pipeline stages
- Both built-in and external commands can be chained

## Platform Compatibility 🖥️

| Feature | Windows | Unix/Linux/Mac |
|---------|---------|----------------|
| Basic commands | ✅ | ✅ |
| Pipelines | ✅ | ✅ |
| Arrow key history | ❌ | ✅ |
| External commands | ✅ | ✅ |
| History file | ✅ | ✅ |

## Limitations ⚠️

This shell does **not** support:
- Input/output redirection (`>`, `<`, `>>`)
- Background jobs (`&`)
- Environment variable expansion (`$VAR`)
- Command substitution (`$(command)`)
- Wildcards/globbing (`*.txt`)
- Logical operators (`;`, `&&`, `||`)

## Contributing 🤝

Contributions are welcome! Feel free to:
- Report bugs
- Suggest new features
- Submit pull requests

## License 📄

This project is open source and available under the MIT License.

## Author 👨‍💻

**Smriti** - [@smritii73](https://github.com/smritii73)
**Vaishnavi** - [@vaishnavidhule](https://github.com/vaishnavidhule)
**Jaykit** - [@Jaykit1907](https://github.com/Jaykit1907)
**Nikshit** - [@143Nikshit](https://github.com/143Nikshit)

---
