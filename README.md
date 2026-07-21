![Logo](LogoQuasar.jpg)

---

# 🌌 Perceptron 
**A Domain-Specific Language (DSL) for Mathematical Logic and Decision-Making on Mobile Devices.**

Perceptron is not just a mathematical calculator; it is a **mathematical logic engine**. It is designed to evaluate complex conditions, calculate weighted scores, and execute parameterized algorithms directly on Android devices, bridging the gap between raw math and business/engineering logic.

### 🧠 The Core Philosophy: Math + Logic
While traditional math languages focus on *calculating* a result, Perceptron focuses on *deciding* based on the result. It treats mathematical expressions and logical conditions as first-class citizens, allowing you to build expert systems, diagnostic models, and automated workflows without writing boilerplate code.

### ✨ Key Features
* **Parameterized UI:** Declare variables with `val`, and the app automatically generates a UI overlay to tweak these parameters before execution. No need to rewrite code for different scenarios.
* **Expressive Logic:** `if-then-else` is an expression that returns a value. Combine math and logic seamlessly: `val status = if stress < yield_strength then "Safe" else "Critical"`.
* **Bulletproof Safety:** Built-in `MathGuard` prevents division by zero and invalid roots. `Circuit Breakers` automatically halt infinite loops (max 1M iterations or 3 seconds) to keep the app responsive.
* **System Integration:** Create home screen shortcuts for specific scripts, or use `open_act` to launch other Android apps based on logical conditions.
* **Rich Built-in Context:** Instant access to physical constants (`C`, `G`), time units (`SEC_IN_DAY`), and mathematical constants (`PI`, `PHI`).

### 📝 Quick Example: Weighted Decision Logic
```perceptron
# 1. Input parameters (These can be modified via the UI overlay when loading from a .fst file)
val cough = 1
val fever = 1
val runny_nose = 0

# 2. Calculate weighted scores for diagnostics
val flu_score = (cough * 30) + (fever * 60) + (runny_nose * 10)
val cold_score = (cough * 20) + (fever * 20) + (runny_nose * 60)

# 3. Find the maximum and make a decision
val max_score = max_val(flu_score, cold_score)
val diagnosis = if max_score == flu_score then "Flu" else "Cold"

# 4. Output the result
"Diagnosis: " + diagnosis
```

### 🎯 Ideal For
* **Engineers & Physicists:** Rapid prototyping of formulas with safety guards.
* **Business Analysts:** Building weighted scoring models and financial calculators.
* **AI Enthusiasts:** Implementing and training neural networks (Perceptron can train a Multi-Layer Perceptron for XOR from scratch!).
* **Automation:** Creating logic-driven shortcuts to control device behavior.
