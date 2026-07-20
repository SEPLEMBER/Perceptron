# 🌌 Perceptron Language Documentation (v5.1 "QUASAR")n mechanisms, and a parameterized user interface for seamless numerical computations.

---

## 📑 Table of Contents
1. [Architecture & Security](#-1-architecture--security)
2. [Basic Syntax & Data Types](#-2-basic-syntax--data-types)
3. [Operators](#-3-operators)
4. [Control Flow](#-4-control-flow)
5. [Functions](#-5-functions)
6. [Built-in Constants](#-6-built-in-constants)
7. [System Commands](#-7-system-commands)
8. [Advanced Features](#-8-advanced-features)
9. [Practical Examples](#-9-practical-examples)
10. [App Features (UI/UX) & Portability](#-10-app-features-uiux--portability)

---

## 🧠 1. Architecture & Security

### Engine
The language is powered by an on-device **Recursive Descent Parser** that performs lexical analysis, syntactic parsing, and Abstract Syntax Tree (AST) evaluation directly on the device. It supports:
* Logical expressions.
* Custom user-defined functions.
* Variadic functions (variable number of arguments).

### Parameterized UI
When a script file is loaded, the engine automatically scans for variable declarations matching the pattern `val name = number` (including scientific notation). It then generates a dialog box, allowing the user to modify these parameters *before* execution, without altering the source code.

### Security Mechanisms
* **MathGuard:** Actively prevents critical mathematical errors, including division by zero, even roots of negative numbers, numeric overflow, and `NaN` generation.
* **Circuit Breaker:** Prevents application freezes by enforcing strict limits on loops:
  * Maximum iterations: **1,000,000**
  * Maximum execution time: **3 seconds**
  * *If either limit is exceeded, the loop is forcibly terminated.*
* **Concurrency:** All script evaluations run in a background thread (`Dispatchers.Default`), ensuring the main UI thread remains completely responsive.
* **Data Protection:** The host Activity enforces `FLAG_SECURE`, preventing screenshots and screen recordings of the application.

---

## 📝 2. Basic Syntax & Data Types

### Data Types
* **Numbers:** Automatically inferred as `Int` (`Long`) or `Num` (`Double`). Scientific notation is fully supported (e.g., `250e6`, `1.5e-3`, `2E+10`). `Int` values are automatically promoted to `Num` during mixed-type arithmetic.
* **Strings:** Enclosed in double quotes (`"text"`). Concatenation is performed using the `+` operator.

### Variables
```kotlin
val x = 10          // Variable declaration
const y = 3.14      // Constant declaration (syntactically identical to val)
x = x + 5           // Reassignment (no 'val' keyword needed)
val z = 250e6;      // Semicolons (;) are optional statement separators
`kotlin
# This is a single-line comment (use #, NOT //)
val result = 10 // 3  // This is INTEGER DIVISION, not a comment!
---                 # Visual separator (outputs "---" to the result console)
```
> ⚠️ **Crucial Note:** The `//` sequence is **not** a comment in Perceptron. It is the integer division operator. Always use `#` for comments.

---

## 🔣 3. Operators

| Operator | Description | Example |
| :--- | :--- | :--- |
| `+`, `-`, `*`, `/` | Standard arithmetic | `val a = 10 * 2.5` |
| `^` | Exponentiation | `2 ^ 8` |
| `^^` | Bitwise XOR (`Int` only) | `5 ^^ 3` |
| `%` | Modulo (remainder) | `10 % 3` |
| `//` | Integer division | `10 // 3` |
| `==`, `!=` | Equality / Inequality | `x == 10` |
| `<`, `>`, `<=`, `>=` | Comparison | `x > 5` |
| `and`, `&&` | Logical AND | `x > 5 and y < 10` |
| `or`, `||` | Logical OR | `x == 1 or y == 2` |

---

## 🔀 4. Control Flow

### Conditional Expressions
In Perceptron, `if` is an **expression** that returns a value. It does not use curly braces `{}`.

```kotlin
val status = if x > 100 and y < 50 then "Optimal" else "Out of range"

"Result: " + (if a == b || c == d then "Match" else "No Match")
```

### While Loops
```kotlin
val i = 1
val sum = 0

while i <= 5 do {
    sum = sum + i
    i = i + 1
}

sum # Returns 15
```
> 🛡️ **Security Limit:** Loops are automatically terminated if they exceed 1,000,000 iterations or 3 seconds of execution time.

---

## ⚙️ 5. Functions

### User-Defined Functions
Defined using the `fn` keyword.
```kotlin
fn hypotenuse(a, b) {
    return sqrt(pow(a, 2.0) + pow(b, 2.0))
}

val c = hypotenuse(3.0, 4.0) # Returns 5.0
```

### Built-in Mathematical Functions
* **Powers & Roots:** `pow(base, exp)`, `sqrt(x)`, `cbrt(x)`, `root(x, degree)`
* **Logarithms:** `ln(x)`, `log2(x)`, `log10(x)`, `exp(x)`
* **Trigonometry (Radians):** `sin(x)`, `cos(x)`, `tan(x)`, `asin(x)`, `acos(x)`, `atan(x)`, `sinh(x)`, `cosh(x)`, `tanh(x)`
* **Rounding:** `round(x)`, `floor(x)`, `ceil(x)`
* **Utilities:** `abs(x)`, `min(a, b)`, `max(a, b)`, `toNum(x)`, `toInt(x)`
* **Special:** `rev(x)` — Reverses a string or an integer (e.g., `rev(123)` → `321`).

### Aggregating Functions (Varargs)
Accept a comma-separated list of arguments.
* `sum(a, b, c, ...)` — Sum of all arguments.
* `avg(a, b, c, ...)` — Arithmetic mean.
* `max_val(a, b, c, ...)` — Maximum value.
* `min_val(a, b, c, ...)` — Minimum value.
* `count(a, b, c, ...)` — Number of arguments.
* `median(a, b, c, ...)` — Median value (handles both even and odd argument counts).

```kotlin
val best_score = max_val(score_A, score_B, score_C)
```

---

## 🌍 6. Built-in Constants

Available globally without declaration.

| Category | Constants | Value / Description |
| :--- | :--- | :--- |
| **Math & Logic** | `PI`, `E`, `PHI` | `3.14159...`, `2.71828...`, `1.61803...` |
| | `TRUE`, `FALSE` | `1` and `0` (for logical operations) |
| **Physics** | `C` | Speed of light: `299,792,458` m/s |
| | `G` | Gravity: `9.81` m/s² |
| | `EARTH_R` | Earth radius: `6,371,000` m |
| | `AVOGADRO` | Avogadro's number: `6.022e23` |
| **Computing** | `KB`, `MB`, `GB` | `1024`, `1,048,576`, `1,073,741,824` bytes |
| **Time (Base)** | `MS_IN_SEC`, `SEC_IN_MIN` | `1000`, `60` |
| | `MIN_IN_HOUR`, `HOUR_IN_DAY` | `60`, `24` |
| **Time (Derived)**| `SEC_IN_HOUR`, `SEC_IN_DAY` | `3600`, `86400` |
| | `MS_IN_MIN`, `MS_IN_HOUR`, `MS_IN_DAY`| `60000`, `3600000`, `86400000` |
| **Calendar** | `WEEK`, `MONTH`, `YEAR` | `7`, `30`, `365` days |
| | `MONTH_WITH_DAY` | `31` days |
| **Length (Metric)**| `MM`, `CM`, `M`, `KM` | `0.001`, `0.01`, `1.0`, `1000` meters |
| **Length (Imperial)**| `INCH`, `FOOT`, `YARD` | `0.0254`, `0.3048`, `0.9144` meters |
| | `MILE`, `NAUTICAL_MILE` | `1609.344`, `1852.0` meters |

---

## 🖥️ 7. System Commands

Can be used as standalone lines in REPL mode or at the end of a script.

| Command | Description |
| :--- | :--- |
| `EXIT` or `exit()` | Terminates the application immediately after script execution. |
| `EXIT 2000` or `exit(2000)` | Displays the result, waits 2000ms (2 seconds), then terminates the app. |
| `CLS` or `CLEAR` | Clears the script editor content. |
| `ABOUT` | Displays language version and developer credits. |
| `HELP` or `?` | Prints a brief syntax reference. |
| `VERSION` or `VER` | Prints the current language version. |
| `OPEN ACT <target>` | Launches an Android Activity. If `<target>` is a package name (e.g., `com.android.settings`), it opens the main launcher activity. If formatted as `package/class`, it opens that specific screen. |

---

## 🚀 8. Advanced Features

### Pipeline Operator (`|>`)
Passes the result of the left expression as the **first argument** to the function on the right. Improves readability for chained operations.
```kotlin
# Instead of: round(pow(2.0, 3.0))
2.0 |> pow(3.0) |> round
```

### Assertions (`assert`)
Evaluates a condition. If false, execution halts immediately with a `PerceptronError`. Ideal for input validation.
```kotlin
val load = 500.0
assert load < 1000.0
```

### Homoglyph Normalization (Anti-Typo Security)
The lexer automatically detects and replaces Cyrillic characters that visually mimic Latin letters to prevent hidden bugs (e.g., typos from switching keyboard layouts).
* `рate` → `rate`
* `х` → `x`
This normalization occurs silently before parsing begins.

---

## 📜 9. Practical Examples

### Example 1: Weighted Diagnostic Model
Demonstrates logical selection and weighted scoring without complex data structures.
```kotlin
# --- DIAGNOSTICS: Weighted Symptom Model ---

# 1 = symptom present, 0 = absent
val cough = 1
val fever = 1
val runny_nose = 0

# Symptom weights for different conditions
val flu_score = (cough * 30) + (fever * 60) + (runny_nose * 10)
val cold_score = (cough * 20) + (fever * 20) + (runny_nose * 60)
val allergy_score = (cough * 10) + (fever * 0) + (runny_nose * 90)

# Determine the maximum score
val max_score = max_val(flu_score, cold_score, allergy_score)

# Select the result
val diagnosis = if max_score == flu_score then "Flu (" + flu_score + "%)"
                else (if max_score == cold_score then "Cold (" + cold_score + "%)"
                else "Allergy (" + allergy_score + "%)")

"Preliminary diagnosis: " + diagnosis
```

### Example 2: Engineering Stress Calculation
Demonstrates scientific notation, `assert`, and mathematical functions.
```kotlin
# --- ENGINEERING: Bending Stress Calculation ---

val force = 5000.0
val length = 2.0
val width = 0.1
val height = 0.2
val yield_strength = 250e6

# Moment of inertia
val inertia = (width * pow(height, 3.0)) / 12.0

# Stress calculation
val stress = (force * length * (height / 2.0)) / inertia

"Max stress: " + round(stress / 1e6 * 100.0) / 100.0 + " MPa"

# Safety check
assert stress < yield_strength

"✅ The beam will withstand the load. Safety margin confirmed."
```

---

## 📱 10. App Features (UI/UX) & Portability

### Parameter Overlay & Shortcuts
* **Parameter Overlay:** When a `.fst` script file is loaded, the app parses it for `val` declarations and prompts the user to input values before running.
* **Home Screen Shortcuts:** Users can create a home screen shortcut for a specific script. The app requests persistent URI permissions (`takePersistableUriPermission`), ensuring the shortcut remains functional even after device reboots.

### Stability & Security Focus
Designed for engineering, commercial, and accounting use cases, Perceptron prioritizes execution safety. As of **v5.0**, all known vulnerabilities affecting execution correctness have been resolved. 
> *Note: There is one known edge case reproducible only via a specialized debug script, which may output a stack trace to the console. This is highly unlikely to occur during standard, everyday usage.*

### Portability
The engine's architecture is platform-agnostic. While currently implemented for Android (Kotlin/JVM), the design explicitly allows for porting to platforms utilizing direct machine code generation (e.g., C or Rust) without the need for a virtual machine.
