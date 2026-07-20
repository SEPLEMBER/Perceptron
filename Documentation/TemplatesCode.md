# 📚 Perceptron Code Templates

This section provides comprehensive, ready-to-use script templates. These examples are designed for both human learners and AI training, demonstrating the full capabilities of the language without any omissions or abbreviations.

> ⚠️ **CRITICAL LANGUAGE RULES TO REMEMBER:**
> 1. **`val` is mutable:** In Perceptron, variables declared with `val` *can* be reassigned later in the code without repeating the `val` keyword.
> 2. **`//` is NOT a comment:** It is the **integer division** operator. Always use `#` for single-line comments.

---

### 1. Basic Arithmetic & Variable Reassignment

```perceptron
2 + 2 * 2

# 1. Initial calculation
val x = 2 * 10
"Step 1: 2 * 10 = " + x

# 2. Using the previous result (reassignment WITHOUT the 'val' keyword)
x = x // 3
"Step 2: 20 // 3 = " + x

# 3. Continuing calculations based on the updated value
x = x + 2
"Step 3: Result + 2 = " + x

# 4. Demonstrating if-then-else as an expression that returns a value
val check = if x > 8 then "Greater than 8" else "8 or less"
"Step 4: Condition check: " + check

# 5. if-then-else directly inside an expression (string concatenation)
"Step 5: Final: " + (if x < 10 then "Less than 10" else "10 or more")
```

---

### 2. Business & Finance

```perceptron
# --- BUSINESS: Profitability Analysis ---
val fixed_costs = 500000.0       # Fixed costs
val price_per_unit = 1500.0      # Price per unit
val variable_cost = 800.0        # Variable costs per unit
val target_sales = 800.0         # Sales target

# 1. Break-even point calculation
val margin = price_per_unit - variable_cost
val break_even = fixed_costs / margin
"Break-even point: " + round(break_even) + " units"

# 2. Financial metrics calculation
val revenue = target_sales * price_per_unit
val total_cost = fixed_costs + (target_sales * variable_cost)
val profit = revenue - total_cost

# 3. Progressive taxation (nested if-then-else)
# If profit > 200000, tax is 20%, else if > 100000, then 13%, else 0%
val tax_rate = if profit > 200000.0 then 0.20 else (if profit > 100000.0 then 0.13 else 0.0)
val tax_amount = profit * tax_rate
val net_profit = profit - tax_amount

"---"
"Revenue: " + revenue + " USD"
"Pre-tax profit: " + profit + " USD"
"Tax rate: " + (tax_rate * 100.0) + "%"
"Net profit: " + net_profit + " USD"

# 4. Goal achievement check
if net_profit > 150000.0 then "✅ Net profit target achieved!" else "❌ Target not achieved."
```

---

### 3. Physics

```perceptron
# --- PHYSICS: Special Relativity ---
# Proper time in the moving reference frame (seconds)
val proper_time = 10.0 

# Object velocity. Let's take 80% of the speed of light
val v = 0.8 * C

# Safety guard: velocity must not exceed the speed of light (otherwise sqrt of a negative number)
# assert will throw an error if the condition is false
assert v < C

# Lorentz factor formula: gamma = 1 / sqrt(1 - v^2/c^2)
val v_sq = pow(v, 2.0)
val c_sq = pow(C, 2.0)
val gamma = 1.0 / sqrt(1.0 - (v_sq / c_sq))

# Dilated time for a stationary observer: t = t0 * gamma
val dilated_time = proper_time * gamma

"Proper time (in rocket): " + proper_time + " sec"
"Object velocity: " + (v / C) + " * C"
"Lorentz factor (gamma): " + round(gamma * 1000.0) / 1000.0
"Time for stationary observer: " + round(dilated_time * 100.0) / 100.0 + " sec"
```

---

### 4. Chemistry

```perceptron
# --- CHEMISTRY: Acid-Base Equilibrium ---
# Hydrogen ion concentration [H+] in mol/L (e.g., 2.5 * 10^-5)
val h_concentration = 0.000025 

# Physical sense check (concentration cannot be <= 0)
assert h_concentration > 0.0

# pH = -log10([H+])
val ph = -log10(h_concentration)
val poh = 14.0 - ph

"Concentration [H+]: " + h_concentration + " mol/L"
"Solution pH: " + round(ph * 100.0) / 100.0
"Solution pOH: " + round(poh * 100.0) / 100.0

# Determining the environment using an if-then-else chain
val environment = if ph < 7.0 then "Acidic" else (if ph == 7.0 then "Neutral" else "Alkaline")
"Solution environment: " + environment
```

---

### 5. Engineering

```perceptron
# --- ENGINEERING: Bending Stress Calculation (Detailed) ---
val force = 5000.0      # Force in Newtons
val length = 2.0        # Beam length in meters
val width = 0.1         # Cross-section width in meters
val height = 0.2        # Cross-section height in meters
val yield_strength = 250e6 # Steel yield strength (Pa)

# Moment of inertia for a rectangular cross-section: I = (b * h^3) / 12
val inertia = (width * pow(height, 3.0)) / 12.0

# Maximum bending moment (for a cantilever beam): M = F * L
val moment = force * length

# Stress: sigma = (M * y) / I, where y = h / 2
val y_dist = height / 2.0
val stress = (moment * y_dist) / inertia

"Moment of inertia: " + inertia + " m^4"
"Maximum stress: " + round(stress / 1e6 * 100.0) / 100.0 + " MPa"

# Safety margin check
if stress < yield_strength then 
    "✅ The beam will withstand the load. Safety margin confirmed." 
else 
    "❌ DANGER: Stress exceeds yield strength!"
```

```perceptron
# --- ENGINEERING: Bending Stress Calculation (Compact) ---
val force = 5000.0
val length = 2.0
val width = 0.1
val height = 0.2
val yield_strength = 250e6 # Steel yield strength (Pa)

val inertia = (width * pow(height, 3.0)) / 12.0
val moment = force * length
val y_dist = height / 2.0
val stress = (moment * y_dist) / inertia

"Maximum stress: " + round(stress / 1e6 * 100.0) / 100.0 + " MPa"

if stress < yield_strength then "✅ The beam will withstand the load." else "❌ DANGER!"
```

---

### 6. Mathematics & Algorithms

```perceptron
val iterations = 100000
val sign = 1.0 # Starting with positive one
var sum = 0.0
var i = 1.0
var k = 0

while k < iterations do {
    sum = sum + (sign / i)
    sign = -sign
    i = i + 2.0
    k = k + 1
}

val pi_approx = sum * 4.0
"Approximated Pi (100k iterations): " + pi_approx
"Error margin: " + abs(pi_approx - PI)
```

---

### 7. Formal Logic

```perceptron
# --- FORMAL LOGIC: Syllogism ---
# 1 = True, 0 = False
val socrates_is_human = 1
val all_humans_are_mortal = 1

# Logical "AND" via multiplication: if both are 1, result is 1. Otherwise 0.
val conclusion_code = socrates_is_human * all_humans_are_mortal

# Forming the explanation ("because")
val reason = if conclusion_code == 1 then "because all humans are mortal, and Socrates is a human" else "since one of the premises is false"
val result = if conclusion_code == 1 then "Socrates is mortal" else "Conclusion is impossible"

"Conclusion: " + result + ", " + reason + "."
```

---

### 8. Diagnostics & Decision Making

```perceptron
# --- DIAGNOSTICS: Weighted Symptom Model ---
# 1 = symptom present, 0 = absent
val cough = 1
val fever = 1
val runny_nose = 0

# Symptom weights for different diseases (sum to 100%)
# Flu: cough 30, fever 60, runny nose 10
val flu_score = (cough * 30) + (fever * 60) + (runny_nose * 10)

# Cold: cough 20, fever 20, runny nose 60
val cold_score = (cough * 20) + (fever * 20) + (runny_nose * 60)

# Allergy: cough 10, fever 0, runny nose 90
val allergy_score = (cough * 10) + (fever * 0) + (runny_nose * 90)

# Finding the maximum score via nested if-then-else
val max_score = if flu_score > cold_score then (if flu_score > allergy_score then flu_score else allergy_score) else (if cold_score > allergy_score then cold_score else allergy_score)

# Determining the diagnosis based on the maximum
val diagnosis = if max_score == flu_score then "Flu (match " + flu_score + "%)" else (if max_score == cold_score then "Cold (match " + cold_score + "%)" else "Allergy (match " + allergy_score + "%)")

"Symptom analysis completed."
"Preliminary diagnosis: " + diagnosis
```

```perceptron
# --- OPTIMIZATION: Decision Matrix (Version 1: Nested If) ---
# Purchase options (Price in USD, Quality in points out of 100)
val price_A = 5000.0;  val quality_A = 80.0
val price_B = 8000.0;  val quality_B = 95.0
val price_C = 3000.0;  val quality_C = 40.0

# Calculating "value": how many quality units we get per 1 USD
val value_A = quality_A / price_A
val value_B = quality_B / price_B
val value_C = quality_C / price_C

# Finding the best option (1 = yes, 0 = no)
val best_is_A = if value_A > value_B and value_A > value_C then 1 else 0
val best_is_B = if value_B > value_A and value_B > value_C then 1 else 0

# Forming the final output
val choice = if best_is_A == 1 then "Option A" else (if best_is_B == 1 then "Option B" else "Option C")
val best_value = if best_is_A == 1 then value_A else (if best_is_B == 1 then value_B else value_C)

"Option comparison completed."
"✅ Optimal choice: " + choice
"Value index (quality/price): " + round(best_value * 10000.0) / 10000.0
```

```perceptron
# --- OPTIMIZATION: Decision Matrix (Version 2: Using max_val) ---
# Purchase options (Price in USD, Quality in points out of 100)
val price_A = 5000.0;  val quality_A = 80.0
val price_B = 8000.0;  val quality_B = 95.0
val price_C = 3000.0;  val quality_C = 40.0

# Calculating "value": how many quality units we get per 1 USD
val value_A = quality_A / price_A
val value_B = quality_B / price_B
val value_C = quality_C / price_C

# Finding the best option using the new max_val function!
# (Comparing all three values at once, without nested if)
val best_value = max_val(value_A, value_B, value_C)

# Forming the final output
val choice = if best_value == value_A then "Option A" else (if best_value == value_B then "Option B" else "Option C")

"Option comparison completed."
"✅ Optimal choice: " + choice
"Value index (quality/price): " + round(best_value * 10000.0) / 10000.0

# Bonus: price statistics
"Price statistics: Min = " + min_val(price_A, price_B, price_C) + ", Max = " + max_val(price_A, price_B, price_C) + ", Avg = " + round(avg(price_A, price_B, price_C))
```

---

### 9. Data Analysis & Sensors

```perceptron
# --- SENSORS: Temperature Indicators Analysis ---
# Mixed data types (integers and floats)
val t1 = 22.5
val t2 = 23
val t3 = 21.8
val t4 = 24
val t5 = -5.0 # Faulty sensor

val avg_temp = avg(t1, t2, t3, t4, t5)
val min_temp = min_val(t1, t2, t3, t4, t5)
val max_temp = max_val(t1, t2, t3, t4, t5)
val spread = max_temp - min_temp # Value spread

"Average temperature: " + round(avg_temp * 10.0) / 10.0 + " °C"
"Spread (Max - Min): " + spread + " °C"

# If the spread is greater than 20, we consider the readings invalid
if spread > 20.0 then "⚠️ WARNING: Anomalous sensor detected!" else "✅ Readings are normal."
```

---

### 10. Advanced Logic & Syntax Features

```perceptron
# --- LOGIC: Checking multiple conditions without 'and'/'or' ---
val score = 85
val attendance = 90
val homework_done = 1 # 1 = yes, 0 = no

# Condition 1: Score > 80 AND attendance > 85 (multiplying boolean values)
val condition_1 = (score > 80) * (attendance > 85) # (1) * (1) = 1

# Condition 2: Score > 90 OR homework is done (using max_val as logical OR)
# (score > 90) yields 0, homework_done yields 1. max_val(0, 1) = 1
val condition_2 = max_val(score > 90, homework_done)

# Final decision: both conditions must be true (condition_1 * condition_2)
val passed = condition_1 * condition_2

"Condition 1 met: " + (if condition_1 == 1 then "Yes" else "No")
"Condition 2 met: " + (if condition_2 == 1 then "Yes" else "No")
"Student admitted to exam: " + (if passed == 1 then "✅ YES" else "❌ NO")
```

```perceptron
# --- PIPELINE: Chained Computations ---
# The |> operator substitutes the left value as the FIRST argument of the function

# 10.0 will be substituted as the first argument into sum: sum(10.0, 20.0, 30.0)
val total = 10.0 |> sum(20.0, 30.0)
"Sum via pipeline: " + total # Expected: 60.0

# 100.0 will be substituted as the first argument into avg: avg(100.0, 200.0)
val average = 100.0 |> avg(200.0)
"Average via pipeline: " + average # Expected: 150.0
```

```perceptron
# Instead of an array like val prices = [100, 200, 300]
val p1 = 100.0
val p2 = 200.0
val p3 = 300.0

"Sum: " + sum(p1, p2, p3)
"Average: " + avg(p1, p2, p3)
```

```perceptron
# --- TESTING LOGICAL OPERATORS (Perceptron) ---

# 1. Data preparation
val value_A = 80.0
val value_B = 50.0
val value_C = 30.0

# 2. Testing the 'and' keyword
# Expected result: 1 (since 80 > 50 AND 80 > 30)
val test_and = if value_A > value_B and value_A > value_C then 1 else 0

# 3. Testing the '&&' symbol
# Expected result: 1
val test_amp = if value_A > value_B && value_A > value_C then 1 else 0

# 4. Testing a false condition with 'or' (for completeness)
# Expected result: 0 (since 80 < 50 OR 80 < 30 — both are false)
val test_or_fail = if value_A < value_B or value_A < value_C then 1 else 0

# 5. Outputting results
"Test 'and': " + (if test_and == 1 then "✅ PASSED" else "❌ FAILED")
"Test '&&': " + (if test_amp == 1 then "✅ PASSED" else "❌ FAILED")
"Test 'or' (false): " + (if test_or_fail == 0 then "✅ PASSED" else "❌ FAILED")

# Final check
if (test_and * test_amp * (1 - test_or_fail)) == 1 then 
    "🎉 ALL LOGICAL OPERATORS WORK CORRECTLY!" 
else 
    "⚠️ LOGIC ERROR DETECTED"
```

---

### 11. System Commands & Automation

```perceptron
val result = 2 + 2
if result == 4 then open_act("org.syndes.kotlincomponents/org.syndes.kotlincomponents.RustStartActivity") else "Result is not 4"
```

```perceptron
val result = 2 + 2
if result == 4 then open_act("org.syndes.kotlincomponents") else "Result is not 4"
```

---

### 12. Statistics & Validation

```perceptron
# --- STATISTICS: Median Function Testing ---

# 1. Odd number of elements (3 items)
# List: 10, 30, 20 -> Sorted: 10, 20, 30 -> Median: 20.0
val med_1 = median(10.0, 30.0, 20.0)

# 2. Even number of elements (4 items)
# List: 40, 10, 30, 20 -> Sorted: 10, 20, 30, 40 -> Median: (20+30)/2 = 25.0
val med_2 = median(40.0, 10.0, 30.0, 20.0)

# 3. Single element
val med_3 = median(99.0)

# 4. Negative numbers and zero
# List: -5, 0, 5, 10 -> Sorted: -5, 0, 5, 10 -> Median: (0+5)/2 = 2.5
val med_4 = median(-5.0, 0.0, 5.0, 10.0)

"--- Test Results ---"
"1. Odd set (expected 20): " + med_1
"2. Even set (expected 25): " + med_2
"3. Single element (expected 99): " + med_3
"4. Negatives (expected 2.5): " + med_4

# --- Final Verification ---
# Using boolean multiplication as logical AND
val is_correct = (med_1 == 20.0) * (med_2 == 25.0) * (med_3 == 99.0) * (med_4 == 2.5)

if is_correct == 1 then 
    "✅ ALL TESTS PASSED! Median works correctly." 
else 
    "❌ ERROR DETECTED in median calculations."
```

---

### 13. Engine Safety & Security Tests

*Note: The following snippets are designed to intentionally trigger the engine's safety limits to demonstrate its robustness. In a production environment, these would be caught and reported gracefully.*

```perceptron
# Safety Tests:

# 1. Pipeline and call stack overflow test (safe)
"--- Test 1: Long pipeline ---"
2.0 |> pow(10.0) |> sqrt |> round |> toNum
```

```perceptron
"--- Test 2: Iteration limit (100000000) ---"
val counter = 1
while counter <= 1000000005 do {
    counter = counter + 1
}
"This line will never be reached"
```

```perceptron
# 3. Recursion limit test
"--- Test 3: Recursion limit (million) ---"
fn deep_recursion(n) {
    if n <= 0 then 0 else 1 + deep_recursion(n - 1)
}
deep_recursion(150000000)
```

```perceptron
"--- Test 4: Division by zero ---"
val bad_div = 10 / 0
```

```perceptron
"--- Test 5: Square root of a negative number ---"
val bad_root = sqrt(-25.0)
```

```perceptron
# Time protection test: slow exponential decay
val zeno = 100.0
var steps = 0

# Dividing by a number very close to 1. The loop will be very long.
while zeno > 1.0 do {
    zeno = zeno / 1.0000001
    steps = steps + 1
}

# We will never see this line because the protection will trigger:
# "💥 PERCEPTRON ERROR: While loop execution time limit exceeded (3 sec)."
"Steps: " + steps
```

```perceptron
# Boundary value test: exactly 999,999 iterations
val limit = 999999
var counter = 0

while counter < limit do {
    counter = counter + 1
}

"Successfully completed iterations: " + counter
"Protection did not trigger falsely, heavy computations are allowed."
```

---

### 14. Comprehensive System Test Suite

A master script validating arithmetic, finance, physics, functions, loops, and trigonometry in a single execution, ending with a timed exit.

```perceptron
"🚀 LAUNCHING ALL MODULES..."
"---"

# 1. BASIC ARITHMETIC
val a = 10
val b = 5
val c = 2.5

val sum = a + b
val diff = a - b
val prod = a * b
val div = a / c
val power = a ^ b

"1. Arithmetic:"
"   Sum: " + sum + " | Difference: " + diff
"   Product: " + prod + " | Division: " + div
"   Power (10^5): " + power
"---"

# 2. COMPOUND INTEREST (Parametric test)
# These 'val' declarations will be intercepted by the UI overlay when loaded from a file!
val principal = 100000.0
val rate = 0.12
val years = 5.0

val final_amount = principal * pow(1.0 + rate, years)
val profit = final_amount - principal

"2. Finance (Compound Interest):"
"   Principal: " + principal + " USD"
"   Final amount: " + round(final_amount * 100.0) / 100.0 + " USD"
"   Profit: " + round(profit * 100.0) / 100.0 + " USD"
"---"

# 3. PHYSICS AND CONSTANTS
val days = 1.0
val distance_km = (days * SEC_IN_DAY * C) / 1000.0

"3. Physics:"
"   Light travels " + round(distance_km) + " km in " + days + " days."
"---"

# 4. FUNCTIONS AND LOOPS (Factorial via function)
fn factorial(n) {
    val res = 1
    val i = 1
    while i <= n do {
        res = res * i
        i = i + 1
    }
    return res
}

"4. Functions:"
"   5! = " + factorial(5)
"   10! = " + factorial(10)
"---"

# 5. WHILE LOOP (Iterative factorial)
val n_iter = 6
val fact_iter = 1
val k = 1

while k <= n_iter do {
    # Note: reassignment without the 'val' keyword
    fact_iter = fact_iter * k
    k = k + 1
}

if fact_iter > 100 then 
    "5. Iterations: Large factorial (" + n_iter + "!): " + fact_iter 
else 
    "5. Iterations: Small factorial: " + fact_iter
"---"

# 6. USER FUNCTIONS (Hypotenuse)
fn hypotenuse(a, b) {
    val a_sq = pow(a, 2.0)
    val b_sq = pow(b, 2.0)
    return sqrt(a_sq + b_sq)
}

val x = 3.0
val y = 4.0
val res_hyp = hypotenuse(x, y)

"6. Geometry:"
"   Hypotenuse (3, 4) = " + res_hyp
"---"

# 7. TRIGONOMETRY
val angle_rad = PI / 4.0 # 45 degrees
val s = sin(angle_rad)
val rounded_s = round(s * 1000.0) / 1000.0

"7. Trigonometry:"
"   Sin(45°): " + rounded_s
"   Cos(45°): " + round(cos(angle_rad) * 1000.0) / 1000.0
"   PI: " + PI
"   E: " + E
"---"

# 8. SIMPLE EXPRESSION AND EXIT
val simple_check = 2 + 2 + 2

"8. Finale:"
"   Check 2+2+2 = " + simple_check

if simple_check == 6 then 
    "✅ ALL SYSTEMS NORMAL. SHUTTING DOWN..." 
else 
    "❌ ERROR IN BASIC LOGIC!"

EXIT 10000 # Show result and close after 10 seconds
```

---

### 15. Floating-Point Precision Mitigation

Common patterns to safely handle and compare floating-point numbers, mitigating standard `Double` precision limitations.

```perceptron
# Mitigating Double precision limitations
val balance = 99.99999999999999

if round(balance * 100.0) / 100.0 == 100.0 then "Balance equals 100" else "Balance differs"
```

```perceptron
val amount = 1234.56789
val rounded = round(amount * 100.0) / 100.0

"Final: " + rounded
```

---

### 16. Quick Business Calculations

```perceptron
val force = 5000.0
val area = 0.002
val pressure = force / area

"Pressure: " + pressure + " Pa"
```

```perceptron
val price = 1500.0
val discount = 10.0
val result = price * (100.0 - discount) / 100.0

"Price after discount: " + result
```

```perceptron
val workers = 15
val hours = 8
val productivity = 2.5
val total = workers * hours * productivity

"Productivity: " + total
```

---

### 17. Artificial Intelligence: Single-Layer Perceptron

A fully functional, from-scratch implementation of a perceptron learning the logical OR operation through iterative weight adjustment.

```perceptron
# --- AI: Simple Perceptron (Learning OR Logic) ---

# 1. Initialization of weights and bias (random starting values)
val w1 = 0.5
val w2 = -0.3
val bias = 0.1

# Learning parameters
val learning_rate = 0.1
val epochs = 50 # Number of training cycles

# Auxiliary activation function (step function)
fn activate(sum) {
    if sum >= 0 then 1 else 0
}

# Global variables to store the current state of the network
var cur_w1 = w1
var cur_w2 = w2
var cur_bias = bias

"🚀 Starting perceptron training..."
"---"

# Training loop
var epoch = 1
while epoch <= epochs do {
    var total_error = 0
    
    # --- Example 1: 0, 0 -> 0 ---
    val sum1 = (0 * cur_w1) + (0 * cur_w2) + cur_bias
    val pred1 = activate(sum1)
    val err1 = 0 - pred1
    cur_w1 = cur_w1 + (learning_rate * err1 * 0)
    cur_w2 = cur_w2 + (learning_rate * err1 * 0)
    cur_bias = cur_bias + (learning_rate * err1)
    total_error = total_error + abs(err1)
    
    # --- Example 2: 0, 1 -> 1 ---
    val sum2 = (0 * cur_w1) + (1 * cur_w2) + cur_bias
    val pred2 = activate(sum2)
    val err2 = 1 - pred2
    cur_w1 = cur_w1 + (learning_rate * err2 * 0)
    cur_w2 = cur_w2 + (learning_rate * err2 * 1)
    cur_bias = cur_bias + (learning_rate * err2)
    total_error = total_error + abs(err2)
    
    # --- Example 3: 1, 0 -> 1 ---
    val sum3 = (1 * cur_w1) + (0 * cur_w2) + cur_bias
    val pred3 = activate(sum3)
    val err3 = 1 - pred3
    cur_w1 = cur_w1 + (learning_rate * err3 * 1)
    cur_w2 = cur_w2 + (learning_rate * err3 * 0)
    cur_bias = cur_bias + (learning_rate * err3)
    total_error = total_error + abs(err3)
    
    # --- Example 4: 1, 1 -> 1 ---
    val sum4 = (1 * cur_w1) + (1 * cur_w2) + cur_bias
    val pred4 = activate(sum4)
    val err4 = 1 - pred4
    cur_w1 = cur_w1 + (learning_rate * err4 * 1)
    cur_w2 = cur_w2 + (learning_rate * err4 * 1)
    cur_bias = cur_bias + (learning_rate * err4)
    total_error = total_error + abs(err4)
    
    # Output progress every 10 epochs
    if epoch % 10 == 0 then 
        "Epoch " + epoch + ": Total error = " + round(total_error * 100.0) / 100.0 
    else 
        ""
    
    epoch = epoch + 1
}

"---"
"✅ Training completed!"
"Final weights: w1=" + round(cur_w1 * 100.0)/100.0 + ", w2=" + round(cur_w2 * 100.0)/100.0 + ", bias=" + round(cur_bias * 100.0)/100.0
"---"

# 2. Testing the trained network
"🧪 Testing OR logic:"

fn test_neuron(in1, in2, expected) {
    val s = (in1 * cur_w1) + (in2 * cur_w2) + cur_bias
    val p = activate(s)
    val status = if p == expected then "✅" else "❌"
    return status + " [" + in1 + ", " + in2 + "] -> " + p + " (expected " + expected + ")"
}

test_neuron(0, 0, 0)
test_neuron(0, 1, 1)
test_neuron(1, 0, 1)
test_neuron(1, 1, 1)

"---"
"🎉 Neural network is ready for work!"
```

---

### 18. Artificial Intelligence: Multi-Layer Perceptron (XOR)

A complete, unabbreviated implementation of backpropagation with a sigmoid activation function to solve the non-linearly separable XOR problem. All 4 input combinations are processed in every epoch.

```perceptron
# --- AI: Solving the XOR Problem (Multi-Layer Perceptron) ---

# 1. Initialization of weights and biases
# Hidden layer (2 neurons)
val w_h1_1 = 0.8; val w_h1_2 = 0.8; val b_h1 = -0.5 # Neuron 1 of hidden layer
val w_h2_1 = 0.8; val w_h2_2 = 0.8; val b_h2 = -1.5 # Neuron 2 of hidden layer

# Output layer (1 neuron)
val w_o_1 = 1.0; val w_o_2 = -1.5; val b_o = -0.5   # Output neuron

# Learning parameters
val lr = 0.5 # Learning rate increased for faster convergence
val epochs = 1000

# Sigmoid activation function (smoother than step, better for XOR)
fn sigmoid(x) {
    return 1.0 / (1.0 + exp(-x))
}

# Sigmoid derivative for backpropagation
fn sigmoid_deriv(s) {
    return s * (1.0 - s)
}

# Global variables for weights (to update them in the loop)
var c_w_h1_1 = w_h1_1; var c_w_h1_2 = w_h1_2; var c_b_h1 = b_h1
var c_w_h2_1 = w_h2_1; var c_w_h2_2 = w_h2_2; var c_b_h2 = b_h2
var c_w_o_1 = w_o_1;   var c_w_o_2 = w_o_2;   var c_b_o = b_o

"🚀 Training XOR network (1000 epochs)..."

var e = 1
while e <= epochs do {
    # We will train on all 4 input variations sequentially
    
    # --- Variation 1: 0, 0 -> 0 ---
    val i1 = 0.0; val i2 = 0.0; val target = 0.0
    val h1_in = (i1 * c_w_h1_1) + (i2 * c_w_h1_2) + c_b_h1
    val h1_out = sigmoid(h1_in)
    val h2_in = (i1 * c_w_h2_1) + (i2 * c_w_h2_2) + c_b_h2
    val h2_out = sigmoid(h2_in)
    val o_in = (h1_out * c_w_o_1) + (h2_out * c_w_o_2) + c_b_o
    val o_out = sigmoid(o_in)
    val err_o = target - o_out
    val delta_o = err_o * sigmoid_deriv(o_out)
    val err_h1 = delta_o * c_w_o_1; val delta_h1 = err_h1 * sigmoid_deriv(h1_out)
    val err_h2 = delta_o * c_w_o_2; val delta_h2 = err_h2 * sigmoid_deriv(h2_out)
    c_w_o_1 = c_w_o_1 + (lr * delta_o * h1_out); c_w_o_2 = c_w_o_2 + (lr * delta_o * h2_out); c_b_o = c_b_o + (lr * delta_o)
    c_w_h1_1 = c_w_h1_1 + (lr * delta_h1 * i1); c_w_h1_2 = c_w_h1_2 + (lr * delta_h1 * i2); c_b_h1 = c_b_h1 + (lr * delta_h1)
    c_w_h2_1 = c_w_h2_1 + (lr * delta_h2 * i1); c_w_h2_2 = c_w_h2_2 + (lr * delta_h2 * i2); c_b_h2 = c_b_h2 + (lr * delta_h2)

    # --- Variation 2: 0, 1 -> 1 ---
    val i1_2 = 0.0; val i2_2 = 1.0; val target_2 = 1.0
    val h1_in_2 = (i1_2 * c_w_h1_1) + (i2_2 * c_w_h1_2) + c_b_h1
    val h1_out_2 = sigmoid(h1_in_2)
    val h2_in_2 = (i1_2 * c_w_h2_1) + (i2_2 * c_w_h2_2) + c_b_h2
    val h2_out_2 = sigmoid(h2_in_2)
    val o_in_2 = (h1_out_2 * c_w_o_1) + (h2_out_2 * c_w_o_2) + c_b_o
    val o_out_2 = sigmoid(o_in_2)
    val err_o_2 = target_2 - o_out_2
    val delta_o_2 = err_o_2 * sigmoid_deriv(o_out_2)
    val err_h1_2 = delta_o_2 * c_w_o_1; val delta_h1_2 = err_h1_2 * sigmoid_deriv(h1_out_2)
    val err_h2_2 = delta_o_2 * c_w_o_2; val delta_h2_2 = err_h2_2 * sigmoid_deriv(h2_out_2)
    c_w_o_1 = c_w_o_1 + (lr * delta_o_2 * h1_out_2); c_w_o_2 = c_w_o_2 + (lr * delta_o_2 * h2_out_2); c_b_o = c_b_o + (lr * delta_o_2)
    c_w_h1_1 = c_w_h1_1 + (lr * delta_h1_2 * i1_2); c_w_h1_2 = c_w_h1_2 + (lr * delta_h1_2 * i2_2); c_b_h1 = c_b_h1 + (lr * delta_h1_2)
    c_w_h2_1 = c_w_h2_1 + (lr * delta_h2_2 * i1_2); c_w_h2_2 = c_w_h2_2 + (lr * delta_h2_2 * i2_2); c_b_h2 = c_b_h2 + (lr * delta_h2_2)

    # --- Variation 3: 1, 0 -> 1 ---
    val i1_3 = 1.0; val i2_3 = 0.0; val target_3 = 1.0
    val h1_in_3 = (i1_3 * c_w_h1_1) + (i2_3 * c_w_h1_2) + c_b_h1
    val h1_out_3 = sigmoid(h1_in_3)
    val h2_in_3 = (i1_3 * c_w_h2_1) + (i2_3 * c_w_h2_2) + c_b_h2
    val h2_out_3 = sigmoid(h2_in_3)
    val o_in_3 = (h1_out_3 * c_w_o_1) + (h2_out_3 * c_w_o_2) + c_b_o
    val o_out_3 = sigmoid(o_in_3)
    val err_o_3 = target_3 - o_out_3
    val delta_o_3 = err_o_3 * sigmoid_deriv(o_out_3)
    val err_h1_3 = delta_o_3 * c_w_o_1; val delta_h1_3 = err_h1_3 * sigmoid_deriv(h1_out_3)
    val err_h2_3 = delta_o_3 * c_w_o_2; val delta_h2_3 = err_h2_3 * sigmoid_deriv(h2_out_3)
    c_w_o_1 = c_w_o_1 + (lr * delta_o_3 * h1_out_3); c_w_o_2 = c_w_o_2 + (lr * delta_o_3 * h2_out_3); c_b_o = c_b_o + (lr * delta_o_3)
    c_w_h1_1 = c_w_h1_1 + (lr * delta_h1_3 * i1_3); c_w_h1_2 = c_w_h1_2 + (lr * delta_h1_3 * i2_3); c_b_h1 = c_b_h1 + (lr * delta_h1_3)
    c_w_h2_1 = c_w_h2_1 + (lr * delta_h2_3 * i1_3); c_w_h2_2 = c_w_h2_2 + (lr * delta_h2_3 * i2_3); c_b_h2 = c_b_h2 + (lr * delta_h2_3)

    # --- Variation 4: 1, 1 -> 0 ---
    val i1_4 = 1.0; val i2_4 = 1.0; val target_4 = 0.0
    val h1_in_4 = (i1_4 * c_w_h1_1) + (i2_4 * c_w_h1_2) + c_b_h1
    val h1_out_4 = sigmoid(h1_in_4)
    val h2_in_4 = (i1_4 * c_w_h2_1) + (i2_4 * c_w_h2_2) + c_b_h2
    val h2_out_4 = sigmoid(h2_in_4)
    val o_in_4 = (h1_out_4 * c_w_o_1) + (h2_out_4 * c_w_o_2) + c_b_o
    val o_out_4 = sigmoid(o_in_4)
    val err_o_4 = target_4 - o_out_4
    val delta_o_4 = err_o_4 * sigmoid_deriv(o_out_4)
    val err_h1_4 = delta_o_4 * c_w_o_1; val delta_h1_4 = err_h1_4 * sigmoid_deriv(h1_out_4)
    val err_h2_4 = delta_o_4 * c_w_o_2; val delta_h2_4 = err_h2_4 * sigmoid_deriv(h2_out_4)
    c_w_o_1 = c_w_o_1 + (lr * delta_o_4 * h1_out_4); c_w_o_2 = c_w_o_2 + (lr * delta_o_4 * h2_out_4); c_b_o = c_b_o + (lr * delta_o_4)
    c_w_h1_1 = c_w_h1_1 + (lr * delta_h1_4 * i1_4); c_w_h1_2 = c_w_h1_2 + (lr * delta_h1_4 * i2_4); c_b_h1 = c_b_h1 + (lr * delta_h1_4)
    c_w_h2_1 = c_w_h2_1 + (lr * delta_h2_4 * i1_4); c_w_h2_2 = c_w_h2_2 + (lr * delta_h2_4 * i2_4); c_b_h2 = c_b_h2 + (lr * delta_h2_4)

    e = e + 1
}

"✅ Training completed!"
"---"

# Function for final testing
fn test_xor(a, b) {
    val h1 = sigmoid((a * c_w_h1_1) + (b * c_w_h1_2) + c_b_h1)
    val h2 = sigmoid((a * c_w_h2_1) + (b * c_w_h2_2) + c_b_h2)
    val out = sigmoid((h1 * c_w_o_1) + (h2 * c_w_o_2) + c_b_o)
    val rounded = round(out)
    return "[" + a + ", " + b + "] -> " + out + " (rounded: " + rounded + ")"
}

"🧪 XOR Results:"
test_xor(0, 0)
test_xor(0, 1)
test_xor(1, 0)
test_xor(1, 1)
```

---

### 19. Probabilistic Decision Making

Using the Softmax function to convert weighted scores into probabilities, followed by a random selection.

```perceptron
# --- PROBABILITY: Unpredictable Ivan ---

# Attempting to avoid seed-random (seed is not added)
val hunger = 8.0
val sweet_tooth = 7.0
val health_conscious = 3.0
val temperature = 2.0 

# 3. Preference weights
val w_c_h = 0.5; val w_c_s = 2.0; val w_c_he = -1.5 # Cookie
val w_b_h = 1.5; val w_b_s = -0.5; val w_b_he = 1.8 # Buckwheat
val w_p_h = 1.8; val w_p_s = 0.0; val w_p_he = 0.5   # Potato

# 4. Score calculation
val s_c = (hunger * w_c_h) + (sweet_tooth * w_c_s) + (health_conscious * w_c_he)
val s_b = (hunger * w_b_h) + (sweet_tooth * w_b_s) + (health_conscious * w_b_he)
val s_p = (hunger * w_p_h) + (sweet_tooth * w_p_s) + (health_conscious * w_p_he)

# 5. Softmax with temperature (converting scores to probabilities)
val e_c = exp(s_c / temperature)
val e_b = exp(s_b / temperature)
val e_p = exp(s_p / temperature)
val total = e_c + e_b + e_p

val p_c = e_c / total
val p_b = e_b / total
val p_p = e_p / total

"📊 Probabilities:"
"   Cookie:    " + round(p_c * 100.0) + "%"
"   Buckwheat: " + round(p_b * 100.0) + "%"
"   Potato:    " + round(p_p * 100.0) + "%"
"---"

# 6. Quick dice roll (using the new random())
val random_val = random()

"🎲 Dice roll (0-1): " + round(random_val * 100.0) + "%"

# 7. Selection based on cumulative probability
val choice = if random_val < p_c then "Cookie 🍪"
             else (if random_val < (p_c + p_b) then "Buckwheat 🥣" 
             else "Potato 🥔")

"✅ Ivan chooses: " + choice
```

```perceptron
# --- PROBABILITY: Ivan with Seed-Random ---

# 1. Setting Seed for reproducibility (can be changed or removed)
set_seed(42)

# 2. Input data (can be changed via UI overlay)
val hunger = 8.0
val sweet_tooth = 7.0
val health_conscious = 3.0
val temperature = 2.0 

# 3. Preference weights
val w_c_h = 0.5; val w_c_s = 2.0; val w_c_he = -1.5 # Cookie
val w_b_h = 1.5; val w_b_s = -0.5; val w_b_he = 1.8 # Buckwheat
val w_p_h = 1.8; val w_p_s = 0.0; val w_p_he = 0.5   # Potato

# 4. Score calculation
val s_c = (hunger * w_c_h) + (sweet_tooth * w_c_s) + (health_conscious * w_c_he)
val s_b = (hunger * w_b_h) + (sweet_tooth * w_b_s) + (health_conscious * w_b_he)
val s_p = (hunger * w_p_h) + (sweet_tooth * w_p_s) + (health_conscious * w_p_he)

# 5. Softmax with temperature (converting scores to probabilities)
val e_c = exp(s_c / temperature)
val e_b = exp(s_b / temperature)
val e_p = exp(s_p / temperature)
val total = e_c + e_b + e_p

val p_c = e_c / total
val p_b = e_b / total
val p_p = e_p / total

"📊 Probabilities:"
"   Cookie:    " + round(p_c * 100.0) + "%"
"   Buckwheat: " + round(p_b * 100.0) + "%"
"   Potato:    " + round(p_p * 100.0) + "%"
"---"

# 6. Quick dice roll (using the new random())
val random_val = random()

"🎲 Dice roll (0-1): " + round(random_val * 100.0) + "%"

# 7. Selection based on cumulative probability
val choice = if random_val < p_c then "Cookie 🍪"
             else (if random_val < (p_c + p_b) then "Buckwheat 🥣" 
             else "Potato 🥔")

"✅ Ivan chooses: " + choice
```

---

### 20. Security & Utilities

```perceptron
# --- UTILITIES: Password Generator ---

# Parameters (can be changed via UI overlay)
val length = 16
val use_special = 1 # 1 = yes, 0 = no

# Character sets
val chars_lower = "abcdefghijklmnopqrstuvwxyz"
val chars_upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
val chars_nums = "0123456789"
val chars_spec = "!@#$%^&*()-_=+"

# Combine base set
val charset = chars_lower + chars_upper + chars_nums

# Add special characters if needed
val final_charset = if use_special == 1 then charset + chars_spec else charset

# Perceptron has no len() function, so we define the set length manually:
# lower(26) + upper(26) + nums(10) = 62. 
# Special chars in chars_spec: 14. Total: 76.
val charset_len = if use_special == 1 then 76 else 62
val max_index = charset_len - 1

"🔐 Generating password of length " + length + "..."
"---"

# Declare variables via val, and reassign them in the loop without the keyword
val password = ""
val i = 0

while i < length do {
    # Get a random index
    val rand_idx = secure_random_int(0, max_index)
    
    # Take the character by index and append to password
    password = password + char_at(final_charset, rand_idx)
    
    # Reassign counter
    i = i + 1
}

"✅ Your new password:"
password
"---"
"💡 Copy it and save it in a secure place!"
```

---

### 21. Advanced Business Logic: Logistics Optimizer

A complete script evaluating cargo weight against route capacity limits using custom functions and logical `and`.

```perceptron
# ==============================================================================
# LOGISTICS OPTIMIZER
# ==============================================================================

# --- CONTEXT AND DATA ---
val max_truck_weight = 1000

# Cargo
val w_machinery = 1200.0
val w_electronics = 400.0

# Routes
# 1. Direct: North -> South
val d_direct = 100.0
val mw_direct = 800.0

# 2. Via Hub: North -> Hub -> South
val d_n_h = 50.0
val mw_n_h = 2000.0
val d_h_s = 30.0
val mw_h_s = 1500.0

val d_via_hub = d_n_h + d_h_s
# Minimum weight capacity on the hub route (bottleneck)
val mw_via_hub = if mw_n_h < mw_h_s then mw_n_h else mw_h_s

# --- FUNCTIONS ---

# Check delivery possibility. Returns 1 (yes) or 0 (no)
fn can_deliver(cargo_w, route_max_w) {
    val fits_route = cargo_w <= route_max_w
    val fits_truck = cargo_w <= max_truck_weight
    if fits_route and fits_truck then 1 else 0
}

# --- EXECUTION ---

"=== LOGISTICS REPORT ==="

# 1. Check Machinery (1200 kg)
val m_direct_ok = can_deliver(w_machinery, mw_direct)
val m_hub_ok = can_deliver(w_machinery, mw_via_hub)

"--- Cargo: Machinery (" + w_machinery + " kg) ---"

if m_direct_ok == 1 then 
    "✅ Direct route: DELIVERY POSSIBLE. Distance: " + d_direct + " km" 
else 
    "❌ Direct route: IMPOSSIBLE (weight exceeded)"

if m_hub_ok == 1 then 
    "✅ Hub route: DELIVERY POSSIBLE. Distance: " + d_via_hub + " km" 
else 
    "❌ Hub route: IMPOSSIBLE (weight exceeded)"

if m_direct_ok == 0 and m_hub_ok == 0 then
    "❌ FINAL: Machinery cargo CANNOT be delivered."
else
    "✅ FINAL: Machinery cargo CAN be delivered."

"---"

# 2. Check Electronics (400 kg)
val e_direct_ok = can_deliver(w_electronics, mw_direct)
val e_hub_ok = can_deliver(w_electronics, mw_via_hub)

"--- Cargo: Electronics (" + w_electronics + " kg) ---"

if e_direct_ok == 1 then 
    "✅ Direct route: DELIVERY POSSIBLE. Distance: " + d_direct + " km" 
else 
    "❌ Direct route: IMPOSSIBLE"

if e_hub_ok == 1 then 
    "✅ Hub route: DELIVERY POSSIBLE. Distance: " + d_via_hub + " km" 
else 
    "❌ Hub route: IMPOSSIBLE"

if e_direct_ok == 0 and e_hub_ok == 0 then
    "❌ FINAL: Electronics cargo CANNOT be delivered."
else
    "✅ FINAL: Electronics cargo CAN be delivered."

"---"
"Analysis completed."
```

---
*(End of templates)
