/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.debug;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import org.htcom.protean.compiler.RuntimeCompiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A complete expression evaluator — zero-dep, hand-rolled.
 * Because protean is a library and consumers cannot extend it at the point of use, it supports Java expression syntax
 * as completely as possible.
 *
 * <p><b>Supported</b>: identifiers (local/this-field), literals, {@code this}, field/getter/indexing, <b>type-aware
 * overload/constructor resolution</b> (widening, autoboxing, supertypes, most-specific), arithmetic, comparison,
 * logical (short-circuit), bitwise/shift, unary (<code>- ! ~</code>), ternary (<code>?:</code>), string
 * <code>+</code> concatenation, primitive and <b>reference-type casts</b> (FQCN), {@code instanceof}, {@code new},
 * FQCN static references, <b>assignment</b> (<code>= += …</code>, local/field/array/static lvalues), lenient handling
 * of generic type arguments (ignored), <b>lambdas</b> (synthesized classes injected into the target VM), and
 * <b>method references</b> ({@code recv::member}/{@code Type::new}, four kinds: static/unbound/bound/constructor).
 * Java expression syntax is fully supported (nothing left out).
 * <p><b>Boundaries</b>: type names for static/cast/instanceof/method-reference must be loaded <b>FQCNs</b>
 * ({@code classesByName} constraint; a not-yet-loaded functional interface is force-loaded via {@code Class.forName}),
 * and lambda/method-reference synthesized bodies and types must be resolvable on the host compilation classpath
 * (in a generic context, specify the lambda parameter types explicitly).
 *
 * <p>The parser builds a <b>thunk (AST) tree</b> and {@link #evaluate} evaluates the root — so short-circuiting and
 * precedence behave correctly. lvalue nodes ({@link LValue}) provide {@code set()}.
 *
 * <p><b>Handling frame invalidation</b>: because invokeMethod invalidates the StackFrame, reads use Value snapshots
 * captured before evaluation, and only local assignment re-acquires the live frame via {@code thread.frame(frameIndex)}
 * to setValue. Method/constructor calls use {@code INVOKE_SINGLE_THREADED}; disabling breakpoints during evaluation is
 * the caller's responsibility.
 */
final class ExpressionEvaluator {

    @FunctionalInterface
    private interface Node {
        Value eval();
    }

    /** Assignable node (local/field/array/static). */
    private interface LValue extends Node {
        void set(Value v);
    }

    /** Common to lambdas and method references — synthesized into an implementation instance once the target functional interface is known. */
    private interface FunctionalArg extends Node {
        Value materialize(ReferenceType iface, String ifaceName);
        @Override default Value eval() {
            throw new IllegalArgumentException("expression evaluation error: a lambda/method reference can only be used in a functional-interface argument position");
        }
    }

    private static final Set<String> PRIMITIVE_TYPES =
            Set.of("int", "long", "double", "float", "short", "byte", "char", "boolean");
    /** Descending by length — for longest-match operator tokenization. */
    private static final String[] OPS = {
            ">>>=", ">>>", ">>=", "<<=", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "=", "+", "-", "*", "/", "%", "&", "|", "^", "<", ">", "!", "~", "?", ":"
    };

    /** Process-global unique names for injected synthesized classes (prevents duplicate defineClass within the same VM). */
    private static final AtomicInteger LAMBDA_SEQ = new AtomicInteger();

    private final VirtualMachine vm;
    private final ThreadReference thread;
    private final int frameIndex;
    private final Map<String, Value> locals;
    private final Map<String, LocalVariable> localDefs;
    private final ObjectReference thisObj;
    private final RuntimeCompiler compiler;    // for lambda synthesis (if null, lambdas are unsupported)
    private final ObjectReference classLoader; // target loader for synthesized class injection (null=bootstrap→system)

    private String src;
    private int pos;

    ExpressionEvaluator(VirtualMachine vm, ThreadReference thread, int frameIndex,
                        Map<String, Value> locals, Map<String, LocalVariable> localDefs,
                        ObjectReference thisObj, RuntimeCompiler compiler, ObjectReference classLoader) {
        this.vm = vm;
        this.thread = thread;
        this.frameIndex = frameIndex;
        this.locals = locals;
        this.localDefs = localDefs;
        this.thisObj = thisObj;
        this.compiler = compiler;
        this.classLoader = classLoader;
    }

    // --- Public entry ---

    Value evaluate(String expression) {
        this.src = expression;
        this.pos = 0;
        Node root = parseAssignment();
        skipWs();
        if (pos < src.length()) {
            throw err("Unparsed characters after expression: '" + src.substring(pos) + "'");
        }
        return root.eval();
    }

    static String render(Value v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof StringReference s) {
            return s.value();
        }
        return v.toString();
    }

    static String typeName(Value v) {
        return v == null ? "null" : v.type().name();
    }

    // --- Parser: precedence descent (low → high) ---

    /** assignment := methodRef | lambda | ternary ( assignOp assignment )?  (right-associative) */
    private Node parseAssignment() {
        Node ref = tryParseMethodRef();
        if (ref != null) {
            return ref;
        }
        Node lambda = tryParseLambda();
        if (lambda != null) {
            return lambda;
        }
        Node left = parseTernary();
        String op = peekOp();
        if (op != null && isAssignOp(op)) {
            consumeOp(op);
            if (!(left instanceof LValue lv)) {
                throw err("Assignment target is not an lvalue: " + op);
            }
            Node right = parseAssignment();
            return () -> {
                Value rv = op.equals("=") ? right.eval()
                        : applyCompound(op, lv.eval(), right.eval());
                lv.set(rv);
                return rv;   // an assignment expression yields the assigned value
            };
        }
        return left;
    }

    /** ternary := or ( '?' assignment ':' ternary )? */
    private Node parseTernary() {
        Node cond = parseOr();
        if ("?".equals(peekOp())) {
            consumeOp("?");
            Node whenTrue = parseAssignment();
            if (!":".equals(peekOp())) {
                throw err("Expected ternary ':'");
            }
            consumeOp(":");
            Node whenFalse = parseTernary();
            return () -> boolVal(cond.eval()) ? whenTrue.eval() : whenFalse.eval();
        }
        return cond;
    }

    private Node parseOr() {
        Node left = parseAnd();
        while ("||".equals(peekOp())) {
            consumeOp("||");
            Node l = left;
            Node r = parseAnd();
            left = () -> vm.mirrorOf(boolVal(l.eval()) || boolVal(r.eval()));
        }
        return left;
    }

    private Node parseAnd() {
        Node left = parseBitOr();
        while ("&&".equals(peekOp())) {
            consumeOp("&&");
            Node l = left;
            Node r = parseBitOr();
            left = () -> vm.mirrorOf(boolVal(l.eval()) && boolVal(r.eval()));
        }
        return left;
    }

    private Node parseBitOr() {
        Node left = parseBitXor();
        while ("|".equals(peekOp())) {
            consumeOp("|");
            Node l = left;
            Node r = parseBitXor();
            left = () -> bitwise('|', l.eval(), r.eval());
        }
        return left;
    }

    private Node parseBitXor() {
        Node left = parseBitAnd();
        while ("^".equals(peekOp())) {
            consumeOp("^");
            Node l = left;
            Node r = parseBitAnd();
            left = () -> bitwise('^', l.eval(), r.eval());
        }
        return left;
    }

    private Node parseBitAnd() {
        Node left = parseEquality();
        while ("&".equals(peekOp())) {
            consumeOp("&");
            Node l = left;
            Node r = parseEquality();
            left = () -> bitwise('&', l.eval(), r.eval());
        }
        return left;
    }

    private Node parseEquality() {
        Node left = parseRelational();
        while (true) {
            String op = peekOp();
            if (!"==".equals(op) && !"!=".equals(op)) {
                return left;
            }
            consumeOp(op);
            Node l = left;
            Node r = parseRelational();
            boolean neg = op.equals("!=");
            left = () -> vm.mirrorOf(neg != valueEquals(l.eval(), r.eval()));
        }
    }

    private Node parseRelational() {
        Node left = parseShift();
        while (true) {
            if (matchKeyword("instanceof")) {
                String type = parseDottedName();
                skipGenerics();
                Node l = left;
                left = () -> vm.mirrorOf(instanceOf(l.eval(), type));
                continue;
            }
            String op = peekOp();
            if (op == null || !(op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">="))) {
                return left;
            }
            consumeOp(op);
            Node l = left;
            Node r = parseShift();
            left = () -> vm.mirrorOf(compare(op, l.eval(), r.eval()));
        }
    }

    private Node parseShift() {
        Node left = parseAdditive();
        while (true) {
            String op = peekOp();
            if (!"<<".equals(op) && !">>".equals(op) && !">>>".equals(op)) {
                return left;
            }
            consumeOp(op);
            Node l = left;
            Node r = parseAdditive();
            left = () -> shift(op, l.eval(), r.eval());
        }
    }

    private Node parseAdditive() {
        Node left = parseMultiplicative();
        while (true) {
            String op = peekOp();
            if (!"+".equals(op) && !"-".equals(op)) {
                return left;
            }
            consumeOp(op);
            Node l = left;
            Node r = parseMultiplicative();
            String o = op;
            left = () -> add(o, l.eval(), r.eval());
        }
    }

    private Node parseMultiplicative() {
        Node left = parseUnary();
        while (true) {
            String op = peekOp();
            if (!"*".equals(op) && !"/".equals(op) && !"%".equals(op)) {
                return left;
            }
            consumeOp(op);
            Node l = left;
            Node r = parseUnary();
            char o = op.charAt(0);
            left = () -> arith(o, l.eval(), r.eval());
        }
    }

    private Node parseUnary() {
        String op = peekOp();
        if ("!".equals(op)) {
            consumeOp("!");
            Node u = parseUnary();
            return () -> vm.mirrorOf(!boolVal(u.eval()));
        }
        if ("-".equals(op)) {
            consumeOp("-");
            Node u = parseUnary();
            return () -> negate(u.eval());
        }
        if ("~".equals(op)) {
            consumeOp("~");
            Node u = parseUnary();
            return () -> bitNot(u.eval());
        }
        return parseCastOrPostfix();
    }

    /** Recognizes a primitive/reference cast, then postfix. */
    private Node parseCastOrPostfix() {
        skipWs();
        if (peek() == '(') {
            int save = pos;
            pos++;
            skipWs();
            if (isIdentStart(peek())) {
                String name = parseDottedName();
                skipGenerics();
                skipWs();
                if (peek() == ')') {
                    if (PRIMITIVE_TYPES.contains(name)) {
                        pos++;
                        Node u = parseUnary();
                        return () -> castPrimitive(name, u.eval());
                    }
                    ReferenceType rt = tryClass(name);
                    if (rt != null) {   // loaded class name → treat as a reference cast (disambiguation)
                        pos++;
                        Node u = parseUnary();
                        return () -> castReference(rt, u.eval());
                    }
                }
            }
            pos = save;   // not a cast → parenthesized expression
        }
        return parsePostfix();
    }

    private Node parsePostfix() {
        Node base = parsePrimary();
        while (true) {
            skipWs();
            if (peek() == '.') {
                pos++;
                String name = parseIdent();
                skipWs();
                if (peek() == '(') {
                    List<Node> args = parseArgs();
                    Node b = base;
                    base = () -> invokeNodes(b.eval(), name, args);
                } else {
                    Node b = base;
                    base = fieldLValue(b, name);
                }
            } else if (peek() == '[') {
                pos++;
                Node idx = parseAssignment();
                skipWs();
                expect(']');
                Node b = base;
                base = arrayLValue(b, idx);
            } else {
                return base;
            }
        }
    }

    private Node parsePrimary() {
        skipWs();
        char c = peek();
        if (c == '(') {
            pos++;
            Node e = parseAssignment();
            skipWs();
            expect(')');
            return e;
        }
        if (c == '"') {
            Value v = vm.mirrorOf(parseString());
            return () -> v;
        }
        if (c == '\'') {
            Value v = vm.mirrorOf(parseChar());
            return () -> v;
        }
        if (Character.isDigit(c)) {
            Value v = parseNumber();
            return () -> v;
        }
        if (isIdentStart(c)) {
            String name = parseIdent();
            switch (name) {
                case "true" -> { return () -> vm.mirrorOf(true); }
                case "false" -> { return () -> vm.mirrorOf(false); }
                case "null" -> { return () -> null; }
                case "this" -> { return this::requireThis; }
                case "new" -> { return parseNew(); }
                default -> { }
            }
            skipWs();
            if (peek() == '(') {
                List<Node> args = parseArgs();          // unqualified method = a method of this
                return () -> invokeNodes(requireThis(), name, args);
            }
            if (localDefs.containsKey(name)) {
                return localLValue(name);
            }
            if (thisObj != null && thisObj.referenceType().fieldByName(name) != null) {
                return fieldLValue(this::requireThis, name);
            }
            return parseStaticFrom(name);              // FQCN static reference
        }
        throw err("Character cannot start an expression: '" + (pos < src.length() ? c : "<end>") + "'");
    }

    private Node parseNew() {
        String typeName = parseDottedName();
        skipGenerics();
        List<Node> args = parseArgs();
        return () -> {
            ClassType ct = requireClassType(typeName);
            Resolved r = resolveArgs(ct.methodsByName("<init>"), args,
                    () -> err("No constructor (" + args.size() + " arg(s)): " + typeName));
            try {
                return ct.newInstance(thread, r.method, r.argv, ObjectReference.INVOKE_SINGLE_THREADED);
            } catch (InvocationException e) {
                throw err("Constructor threw an exception: " + typeName + " → " + e.exception().referenceType().name());
            } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
                throw err("Constructor invocation failed: " + typeName + " — " + e.getMessage());
            }
        };
    }

    private Node parseStaticFrom(String first) {
        StringBuilder cn = new StringBuilder(first);
        ReferenceType rt = tryClass(cn.toString());
        while (rt == null && peek() == '.') {
            int save = pos;
            pos++;
            skipWs();
            if (!isIdentStart(peek())) {
                pos = save;
                break;
            }
            cn.append('.').append(parseIdent());
            rt = tryClass(cn.toString());
        }
        if (rt == null) {
            throw err("Unresolvable identifier/class: " + cn + " (not a local/this field; static requires an FQCN)");
        }
        final ReferenceType type = rt;
        expect('.');
        String member = parseIdent();
        skipWs();
        if (peek() == '(') {
            List<Node> args = parseArgs();
            return () -> invokeStaticNodes(type, member, args);
        }
        return staticFieldLValue(type, member);
    }

    private List<Node> parseArgs() {
        expect('(');
        List<Node> args = new ArrayList<>();
        skipWs();
        if (peek() == ')') {
            pos++;
            return args;
        }
        while (true) {
            args.add(parseAssignment());
            skipWs();
            if (peek() == ',') {
                pos++;
                continue;
            }
            expect(')');
            return args;
        }
    }

    // --- lvalue nodes ---

    private LValue localLValue(String name) {
        return new LValue() {
            @Override public Value eval() {
                return locals.get(name);
            }
            @Override public void set(Value v) {
                try {
                    thread.frame(frameIndex).setValue(localDefs.get(name), v);  // re-acquire the live frame
                    locals.put(name, v);   // sync the snapshot
                } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
                    throw err("Local assignment failed: " + name + " — " + e.getMessage());
                }
            }
        };
    }

    private LValue fieldLValue(Node targetNode, String name) {
        return new LValue() {
            @Override public Value eval() {
                return field(targetNode.eval(), name);
            }
            @Override public void set(Value v) {
                if (!(targetNode.eval() instanceof ObjectReference obj)) {
                    throw err("Field assignment target is not an object: ." + name);
                }
                Field f = obj.referenceType().fieldByName(name);
                if (f == null) {
                    throw err("No such field: " + obj.referenceType().name() + "." + name);
                }
                try {
                    obj.setValue(f, v);
                } catch (InvalidTypeException | ClassNotLoadedException e) {
                    throw err("Field assignment failed: " + name + " — " + e.getMessage());
                }
            }
        };
    }

    private LValue arrayLValue(Node arrNode, Node idxNode) {
        return new LValue() {
            @Override public Value eval() {
                return arrayGet(arrNode.eval(), idxNode.eval());
            }
            @Override public void set(Value v) {
                if (!(arrNode.eval() instanceof ArrayReference arr)) {
                    throw err("Index-assignment target is not an array");
                }
                int i = intIndex(idxNode.eval(), arr.length());
                try {
                    arr.setValue(i, v);
                } catch (InvalidTypeException | ClassNotLoadedException e) {
                    throw err("Array assignment failed: [" + i + "] — " + e.getMessage());
                }
            }
        };
    }

    private LValue staticFieldLValue(ReferenceType rt, String name) {
        return new LValue() {
            @Override public Value eval() {
                return staticField(rt, name);
            }
            @Override public void set(Value v) {
                Field f = rt.fieldByName(name);
                if (f == null || !f.isStatic()) {
                    throw err("No such static field: " + rt.name() + "." + name);
                }
                if (!(rt instanceof ClassType ct)) {
                    throw err("Static assignment target is not a class: " + rt.name());
                }
                try {
                    ct.setValue(f, v);
                } catch (InvalidTypeException | ClassNotLoadedException e) {
                    throw err("Static assignment failed: " + name + " — " + e.getMessage());
                }
            }
        };
    }

    // --- Operator semantics ---

    private Value add(String op, Value a, Value b) {
        if (op.equals("+") && (a instanceof StringReference || b instanceof StringReference)) {
            return vm.mirrorOf(stringOf(a) + stringOf(b));
        }
        return arith(op.charAt(0), a, b);
    }

    private Value arith(char op, Value a, Value b) {
        PrimitiveValue pa = requireNumeric(a);
        PrimitiveValue pb = requireNumeric(b);
        if (isFloating(a) || isFloating(b)) {
            double x = pa.doubleValue();
            double y = pb.doubleValue();
            return vm.mirrorOf(switch (op) {
                case '+' -> x + y;
                case '-' -> x - y;
                case '*' -> x * y;
                case '/' -> x / y;
                case '%' -> x % y;
                default -> throw err("Unknown operator: " + op);
            });
        }
        if (isLong(a) || isLong(b)) {
            return vm.mirrorOf(longArith(op, pa.longValue(), pb.longValue()));
        }
        return vm.mirrorOf((int) longArith(op, pa.intValue(), pb.intValue()));
    }

    private long longArith(char op, long x, long y) {
        try {
            return switch (op) {
                case '+' -> x + y;
                case '-' -> x - y;
                case '*' -> x * y;
                case '/' -> x / y;
                case '%' -> x % y;
                default -> throw err("Unknown operator: " + op);
            };
        } catch (ArithmeticException e) {
            throw err(e.getMessage());
        }
    }

    private Value bitwise(char op, Value a, Value b) {
        PrimitiveValue pa = requireIntegral(a);
        PrimitiveValue pb = requireIntegral(b);
        if (isLong(a) || isLong(b)) {
            long x = pa.longValue();
            long y = pb.longValue();
            return vm.mirrorOf(switch (op) {
                case '&' -> x & y;
                case '|' -> x | y;
                case '^' -> x ^ y;
                default -> throw err("Unknown bitwise operator: " + op);
            });
        }
        int x = pa.intValue();
        int y = pb.intValue();
        return vm.mirrorOf(switch (op) {
            case '&' -> x & y;
            case '|' -> x | y;
            case '^' -> x ^ y;
            default -> throw err("Unknown bitwise operator: " + op);
        });
    }

    private Value shift(String op, Value a, Value b) {
        PrimitiveValue pa = requireIntegral(a);
        int s = requireIntegral(b).intValue();
        if (isLong(a)) {
            long x = pa.longValue();
            return vm.mirrorOf(switch (op) {
                case "<<" -> x << s;
                case ">>" -> x >> s;
                case ">>>" -> x >>> s;
                default -> throw err("Unknown shift: " + op);
            });
        }
        int x = pa.intValue();
        return vm.mirrorOf(switch (op) {
            case "<<" -> x << s;
            case ">>" -> x >> s;
            case ">>>" -> x >>> s;
            default -> throw err("Unknown shift: " + op);
        });
    }

    private boolean compare(String op, Value a, Value b) {
        double x = requireNumeric(a).doubleValue();
        double y = requireNumeric(b).doubleValue();
        return switch (op) {
            case "<" -> x < y;
            case "<=" -> x <= y;
            case ">" -> x > y;
            case ">=" -> x >= y;
            default -> throw err("Unknown comparison: " + op);
        };
    }

    private boolean valueEquals(Value a, Value b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof BooleanValue ba && b instanceof BooleanValue bb) {
            return ba.value() == bb.value();
        }
        if (a instanceof PrimitiveValue pa && b instanceof PrimitiveValue pb) {
            return pa.doubleValue() == pb.doubleValue();
        }
        if (a instanceof ObjectReference oa && b instanceof ObjectReference ob) {
            return oa.equals(ob);
        }
        return false;
    }

    private Value negate(Value v) {
        PrimitiveValue p = requireNumeric(v);
        if (isFloating(v)) {
            return vm.mirrorOf(-p.doubleValue());
        }
        if (isLong(v)) {
            return vm.mirrorOf(-p.longValue());
        }
        return vm.mirrorOf(-p.intValue());
    }

    private Value bitNot(Value v) {
        PrimitiveValue p = requireIntegral(v);
        return isLong(v) ? vm.mirrorOf(~p.longValue()) : vm.mirrorOf(~p.intValue());
    }

    /** Compound assignment: left op right. */
    private Value applyCompound(String assignOp, Value l, Value r) {
        return switch (assignOp) {
            case "+=" -> add("+", l, r);
            case "-=" -> arith('-', l, r);
            case "*=" -> arith('*', l, r);
            case "/=" -> arith('/', l, r);
            case "%=" -> arith('%', l, r);
            case "&=" -> bitwise('&', l, r);
            case "|=" -> bitwise('|', l, r);
            case "^=" -> bitwise('^', l, r);
            case "<<=" -> shift("<<", l, r);
            case ">>=" -> shift(">>", l, r);
            case ">>>=" -> shift(">>>", l, r);
            default -> throw err("Unknown compound assignment: " + assignOp);
        };
    }

    private Value castPrimitive(String kind, Value v) {
        if (kind.equals("boolean")) {
            if (!(v instanceof BooleanValue bv)) {
                throw err("boolean cast target is not a boolean");
            }
            return vm.mirrorOf(bv.value());
        }
        PrimitiveValue p = requireNumeric(v);
        return switch (kind) {
            case "int" -> vm.mirrorOf(p.intValue());
            case "long" -> vm.mirrorOf(p.longValue());
            case "double" -> vm.mirrorOf(p.doubleValue());
            case "float" -> vm.mirrorOf(p.floatValue());
            case "short" -> vm.mirrorOf(p.shortValue());
            case "byte" -> vm.mirrorOf(p.byteValue());
            case "char" -> vm.mirrorOf(p.charValue());
            default -> throw err("Unknown cast: " + kind);
        };
    }

    private Value castReference(ReferenceType type, Value v) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof ObjectReference obj)) {
            throw err("Reference cast target is not an object: (" + type.name() + ")");
        }
        if (!isAssignable(obj.referenceType(), type.name())) {
            throw err("ClassCastException: " + obj.referenceType().name() + " → " + type.name());
        }
        return v;   // dynamic dispatch — value unchanged
    }

    private boolean instanceOf(Value v, String typeName) {
        return v instanceof ObjectReference obj && isAssignable(obj.referenceType(), typeName);
    }

    // --- Identifier/field/method/array/static resolution ---

    private Value field(Value target, String name) {
        if (!(target instanceof ObjectReference obj)) {
            throw err("Field access target is not an object: ." + name);
        }
        Field f = obj.referenceType().fieldByName(name);
        if (f == null) {
            throw err("No such field: " + obj.referenceType().name() + "." + name);
        }
        return obj.getValue(f);
    }

    /** Invokes an instance method with parsed argument nodes (including lambdas) — resolves the overload, then synthesizes lambdas to the target interface. */
    private Value invokeNodes(Value target, String name, List<Node> argNodes) {
        if (!(target instanceof ObjectReference obj)) {
            throw err("Method-call target is not an object: ." + name + "()");
        }
        ReferenceType rt = obj.referenceType();
        Resolved r = resolveArgs(rt.methodsByName(name), argNodes,
                () -> err("No such method (" + argNodes.size() + " arg(s)): " + rt.name() + "." + name));
        return invoke(obj, r.method, r.argv, name);
    }

    private Value invokeStaticNodes(ReferenceType rt, String name, List<Node> argNodes) {
        Resolved r = resolveArgs(rt.methodsByName(name), argNodes,
                () -> err("No such static method (" + argNodes.size() + " arg(s)): " + rt.name() + "." + name));
        try {
            // static methods can live on interfaces (Java 8+) as well as classes.
            if (rt instanceof ClassType ct) {
                return ct.invokeMethod(thread, r.method, r.argv, ObjectReference.INVOKE_SINGLE_THREADED);
            }
            if (rt instanceof InterfaceType it) {
                return it.invokeMethod(thread, r.method, r.argv, ObjectReference.INVOKE_SINGLE_THREADED);
            }
            throw err("Static method-call target is not a class/interface: " + rt.name());
        } catch (InvocationException e) {
            throw err("Static method threw an exception: " + name + "() → " + e.exception().referenceType().name());
        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            throw err("Static method invocation failed: " + name + "() — " + e.getMessage());
        }
    }

    /** Internal instance method call (with Value args, no lambdas) — e.g. toString(). */
    private Value invoke(Value target, String name, List<Value> args) {
        if (!(target instanceof ObjectReference obj)) {
            throw err("Method-call target is not an object: ." + name + "()");
        }
        Method m = pickCallable(obj.referenceType().methodsByName(name), args,
                () -> err("No such method (" + args.size() + " arg(s)): " + obj.referenceType().name() + "." + name));
        return invoke(obj, m, args, name);
    }

    private Value invoke(ObjectReference obj, Method m, List<Value> args, String name) {
        try {
            return obj.invokeMethod(thread, m, args, ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (InvocationException e) {
            throw err("Target method threw an exception: " + name + "() → " + e.exception().referenceType().name());
        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            throw err("Method invocation failed: " + name + "() — " + e.getMessage());
        }
    }

    private Value staticField(ReferenceType rt, String name) {
        Field f = rt.fieldByName(name);
        if (f == null || !f.isStatic()) {
            throw err("No such static field: " + rt.name() + "." + name);
        }
        return rt.getValue(f);
    }

    private Value arrayGet(Value target, Value idx) {
        if (!(target instanceof ArrayReference arr)) {
            throw err("Indexing target is not an array");
        }
        return arr.getValue(intIndex(idx, arr.length()));
    }

    private int intIndex(Value idx, int length) {
        if (!(idx instanceof PrimitiveValue p) || idx instanceof BooleanValue) {
            throw err("Array index is not an integer");
        }
        int i = p.intValue();
        if (i < 0 || i >= length) {
            throw err("Array index out of range: " + i + " (length " + length + ")");
        }
        return i;
    }

    // --- Overload/constructor resolution (type-aware) ---

    private Method pickCallable(List<Method> candidates, List<Value> args,
                                java.util.function.Supplier<RuntimeException> onMiss) {
        Method best = null;
        int bestScore = -1;
        Method arityFallback = null;
        for (Method m : candidates) {
            if (m.isAbstract()) {
                continue;
            }
            List<String> pts = m.argumentTypeNames();
            if (pts.size() != args.size()) {
                continue;
            }
            if (arityFallback == null) {
                arityFallback = m;
            }
            int score = applicabilityScore(pts, args);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        if (best != null && bestScore >= 0) {
            return best;   // highest type-applicable score (most-specific approximation)
        }
        if (arityFallback != null) {
            return arityFallback;   // if type info cannot narrow it, first arity-matching candidate (best-effort)
        }
        throw onMiss.get();
    }

    /** Sum of scores if every argument is assignable to its parameter (exact match 2, assignable 1). -1 if any is not. */
    private int applicabilityScore(List<String> paramTypes, List<Value> args) {
        int score = 0;
        for (int i = 0; i < args.size(); i++) {
            String pt = paramTypes.get(i);
            Value a = args.get(i);
            if (exactMatch(pt, a)) {
                score += 2;
            } else if (assignableToParam(pt, a)) {
                score += 1;
            } else {
                return -1;
            }
        }
        return score;
    }

    private boolean exactMatch(String paramType, Value a) {
        if (a == null) {
            return false;
        }
        if (a instanceof PrimitiveValue) {
            return paramType.equals(a.type().name());
        }
        if (a instanceof ObjectReference obj) {
            return paramType.equals(obj.referenceType().name());
        }
        return false;
    }

    private boolean assignableToParam(String paramType, Value a) {
        boolean primParam = PRIMITIVE_TYPES.contains(paramType);
        if (a == null) {
            return !primParam;   // null → reference parameters only
        }
        if (a instanceof BooleanValue) {
            return paramType.equals("boolean") || paramType.equals("java.lang.Boolean")
                    || paramType.equals("java.lang.Object");
        }
        if (a instanceof PrimitiveValue) {
            String k = a.type().name();
            if (primParam) {
                return primitiveWidens(k, paramType);
            }
            String wrapper = wrapperName(k);   // autoboxing
            return paramType.equals(wrapper) || paramType.equals("java.lang.Object")
                    || paramType.equals("java.lang.Number");
        }
        if (a instanceof ObjectReference obj) {
            return !primParam && isAssignable(obj.referenceType(), paramType);
        }
        return false;
    }

    /** Whether type {@code from} is assignable to {@code toName} (FQCN) — self + superclasses + interfaces. */
    private boolean isAssignable(ReferenceType from, String toName) {
        if (toName.equals("java.lang.Object")) {
            return true;
        }
        Set<String> names = new HashSet<>();
        collectSupertypes(from, names);
        return names.contains(toName);
    }

    private void collectSupertypes(ReferenceType t, Set<String> names) {
        if (!names.add(t.name())) {
            return;
        }
        if (t instanceof ClassType c) {
            if (c.superclass() != null) {
                collectSupertypes(c.superclass(), names);
            }
            for (InterfaceType i : c.interfaces()) {
                collectSupertypes(i, names);
            }
        } else if (t instanceof InterfaceType it) {
            for (InterfaceType s : it.superinterfaces()) {
                collectSupertypes(s, names);
            }
        }
    }

    private static final int RANK_BYTE = 0;
    private static final int RANK_SHORT = 1;
    private static final int RANK_INT = 2;
    private static final int RANK_LONG = 3;
    private static final int RANK_FLOAT = 4;
    private static final int RANK_DOUBLE = 5;

    private boolean primitiveWidens(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        if (from.equals("boolean") || to.equals("boolean")) {
            return false;
        }
        int toRank = numericRank(to);
        if (from.equals("char")) {
            return toRank >= RANK_INT;   // char → int/long/float/double
        }
        return numericRank(from) <= toRank;
    }

    private int numericRank(String t) {
        return switch (t) {
            case "byte" -> RANK_BYTE;
            case "short" -> RANK_SHORT;
            case "int", "char" -> RANK_INT;
            case "long" -> RANK_LONG;
            case "float" -> RANK_FLOAT;
            case "double" -> RANK_DOUBLE;
            default -> Integer.MAX_VALUE;
        };
    }

    private String wrapperName(String prim) {
        return switch (prim) {
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "double" -> "java.lang.Double";
            case "float" -> "java.lang.Float";
            case "short" -> "java.lang.Short";
            case "byte" -> "java.lang.Byte";
            case "char" -> "java.lang.Character";
            case "boolean" -> "java.lang.Boolean";
            default -> "java.lang.Object";
        };
    }

    // --- Lambdas: parsing + synthesized class injection ---

    private static final Pattern IDENT_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** Lambda parameter (if type=null, unspecified → use the SAM parameter type). */
    private record LParam(String type, String name) {}

    /** Lambda expression node — materialized only in a functional-interface argument position. */
    private final class LambdaExpr implements FunctionalArg {
        final List<LParam> params;
        final String body;
        LambdaExpr(List<LParam> params, String body) {
            this.params = params;
            this.body = body;
        }
        @Override public Value materialize(ReferenceType iface, String ifaceName) {
            return materializeLambda(this, iface, ifaceName);
        }
    }

    /** Method reference node ({@code recv::member}, {@code Type::new}). Synthesized into an equivalent call once the SAM is known. */
    private final class MethodRefExpr implements FunctionalArg {
        final String recv;
        final String member;
        MethodRefExpr(String recv, String member) {
            this.recv = recv;
            this.member = member;
        }
        @Override public Value materialize(ReferenceType iface, String ifaceName) {
            return materializeMethodRef(this, iface, ifaceName);
        }
    }

    /** Method-reference detection: `Dotted.Name::member` / `var::member` / `this::member` / `Type::new`. */
    private Node tryParseMethodRef() {
        int save = pos;
        skipWs();
        if (!isIdentStart(peek())) {
            return null;
        }
        int start = pos;
        while (pos < src.length() && (isIdentPart(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        String recv = src.substring(start, pos);
        skipWs();
        if (pos + 1 < src.length() && src.charAt(pos) == ':' && src.charAt(pos + 1) == ':') {
            pos += 2;
            String member = parseIdent();   // includes "new"
            return new MethodRefExpr(recv, member);
        }
        pos = save;
        return null;
    }

    /** Lambda detection: `ident -> ...` or `( params ) -> ...`. Otherwise restores pos and returns null. */
    private Node tryParseLambda() {
        int save = pos;
        skipWs();
        char c = peek();
        if (isIdentStart(c)) {
            parseIdent();
            boolean arrow = isArrow();
            pos = save;
            if (arrow) {
                skipWs();
                String id = parseIdent();
                consumeArrow();
                return finishLambda(List.of(new LParam(null, id)));
            }
        } else if (c == '(') {
            String inside = scanBalancedParens();
            if (inside != null && isArrow()) {
                consumeArrow();
                return finishLambda(parseParamList(inside));
            }
            pos = save;
        }
        return null;
    }

    private LambdaExpr finishLambda(List<LParam> params) {
        skipWs();
        int bodyStart = pos;
        scanBalancedBody();
        String body = src.substring(bodyStart, pos).trim();
        if (body.isEmpty()) {
            throw err("Lambda body is empty");
        }
        return new LambdaExpr(params, body);
    }

    private boolean isArrow() {
        skipWs();
        return pos + 1 < src.length() && src.charAt(pos) == '-' && src.charAt(pos + 1) == '>';
    }

    private void consumeArrow() {
        skipWs();
        pos += 2;
    }

    /** Consumes from '(' to the matching ')' and returns the inner text. null on failure. */
    private String scanBalancedParens() {
        skipWs();
        if (peek() != '(') {
            return null;
        }
        int startInner = pos + 1;
        int depth = 0;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (ch == '"' || ch == '\'') {
                skipLiteral();
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    String inner = src.substring(startInner, pos);
                    pos++;
                    return inner;
                }
            }
            pos++;
        }
        return null;
    }

    /** Scans until a depth-0 terminator (,)]}) or EOF (skipping literals). Leaves pos at the terminator. */
    private void scanBalancedBody() {
        int depth = 0;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (ch == '"' || ch == '\'') {
                skipLiteral();
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                if (depth == 0) {
                    return;
                }
                depth--;
            } else if (ch == ',' && depth == 0) {
                return;
            }
            pos++;
        }
    }

    private void skipLiteral() {
        char quote = src.charAt(pos++);
        while (pos < src.length()) {
            char ch = src.charAt(pos++);
            if (ch == '\\') {
                if (pos < src.length()) {
                    pos++;
                }
            } else if (ch == quote) {
                return;
            }
        }
    }

    private List<LParam> parseParamList(String text) {
        List<LParam> params = new ArrayList<>();
        for (String part : splitTopLevel(text)) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int sp = lastTopLevelSpace(p);
            if (sp < 0) {
                params.add(new LParam(null, p));
            } else {
                params.add(new LParam(p.substring(0, sp).trim(), p.substring(sp + 1).trim()));
            }
        }
        return params;
    }

    private List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int angle = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == '<') {
                angle++;
            } else if (ch == '>') {
                if (angle > 0) {
                    angle--;
                }
            } else if (ch == ',' && depth == 0 && angle == 0) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    private int lastTopLevelSpace(String p) {
        int depth = 0;
        int angle = 0;
        int sp = -1;
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (ch == '<') {
                angle++;
            } else if (ch == '>') {
                if (angle > 0) {
                    angle--;
                }
            } else if (ch == '(' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == ']') {
                depth--;
            } else if (Character.isWhitespace(ch) && depth == 0 && angle == 0) {
                sp = i;
            }
        }
        return sp;
    }

    // --- Argument resolution (including lambdas) ---

    private record Resolved(Method method, List<Value> argv) {}

    private Resolved resolveArgs(List<Method> candidates, List<Node> argNodes,
                                 java.util.function.Supplier<RuntimeException> onMiss) {
        int n = argNodes.size();
        boolean[] isLambda = new boolean[n];
        Value[] concrete = new Value[n];
        for (int i = 0; i < n; i++) {
            if (argNodes.get(i) instanceof FunctionalArg) {
                isLambda[i] = true;
            } else {
                concrete[i] = argNodes.get(i).eval();
            }
        }
        Method best = null;
        int bestScore = -1;
        Method arity = null;
        for (Method m : candidates) {
            if (m.isAbstract()) {
                continue;
            }
            List<String> pts = m.argumentTypeNames();
            if (pts.size() != n) {
                continue;
            }
            if (arity == null) {
                arity = m;
            }
            int score = 0;
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                if (isLambda[i]) {
                    if (isFunctionalInterface(pts.get(i))) {
                        score += 1;
                    } else {
                        ok = false;
                        break;
                    }
                } else {
                    int s = argScore(pts.get(i), concrete[i]);
                    if (s < 0) {
                        ok = false;
                        break;
                    }
                    score += s;
                }
            }
            if (ok && score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        Method chosen = best != null ? best : arity;
        if (chosen == null) {
            throw onMiss.get();
        }
        List<String> pts = chosen.argumentTypeNames();
        List<Value> argv = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (isLambda[i]) {
                argv.add(((FunctionalArg) argNodes.get(i)).materialize(loadType(pts.get(i)), pts.get(i)));
            } else {
                argv.add(concrete[i]);
            }
        }
        return new Resolved(chosen, argv);
    }

    private int argScore(String paramType, Value a) {
        if (exactMatch(paramType, a)) {
            return 2;
        }
        if (assignableToParam(paramType, a)) {
            return 1;
        }
        return -1;
    }

    private boolean isFunctionalInterface(String typeName) {
        return loadType(typeName) instanceof InterfaceType it && findSam(it) != null;
    }

    /** Looks up a loaded type; if not loaded, force-loads it in the target VM and re-queries (a functional interface may not yet be loaded because it hasn't been used). */
    private ReferenceType loadType(String name) {
        ReferenceType rt = tryClass(name);
        if (rt != null) {
            return rt;
        }
        try {
            ClassType classCls = (ClassType) vm.classesByName("java.lang.Class").get(0);
            Method forName = null;
            for (Method m : classCls.methodsByName("forName")) {
                if (m.argumentTypeNames().equals(
                        List.of("java.lang.String", "boolean", "java.lang.ClassLoader"))) {
                    forName = m;
                    break;
                }
            }
            if (forName != null) {
                List<Value> a = new ArrayList<>();
                a.add(vm.mirrorOf(name));
                a.add(vm.mirrorOf(true));
                a.add(classLoader);   // null means the bootstrap loader
                classCls.invokeMethod(thread, forName, a, ObjectReference.INVOKE_SINGLE_THREADED);
            }
        } catch (InvalidTypeException | ClassNotLoadedException
                 | IncompatibleThreadStateException | InvocationException | RuntimeException ignored) {
            // load failed → return null below
        }
        return tryClass(name);
    }

    /** The single abstract method of a functional interface (excluding Object methods). null if none. */
    private Method findSam(InterfaceType it) {
        Method sam = null;
        for (Method m : it.allMethods()) {
            if (!m.isAbstract() || m.isStatic()) {
                continue;
            }
            String nm = m.name();
            if (nm.equals("equals") || nm.equals("hashCode") || nm.equals("toString")) {
                continue;
            }
            if (sam == null) {
                sam = m;   // if several, use the first (approximation)
            }
        }
        return sam;
    }

    private InterfaceType requireFunctional(ReferenceType iface, String ifaceName) {
        if (compiler == null) {
            throw err("Lambdas/method references are only supported via debug.evaluate(RuntimeCompiler)");
        }
        if (!(iface instanceof InterfaceType it) || findSam(it) == null) {
            throw err("Not a functional interface or not loaded (FQCN required): " + ifaceName);
        }
        return it;
    }

    /** Synthesizes and injects the lambda as an implementation instance of the target functional interface. */
    private Value materializeLambda(LambdaExpr lam, ReferenceType iface, String ifaceName) {
        InterfaceType it = requireFunctional(iface, ifaceName);
        Method sam = findSam(it);
        List<String> samParams = sam.argumentTypeNames();
        if (samParams.size() != lam.params.size()) {
            throw err("Lambda parameter count mismatch: " + ifaceName + "." + sam.name() + " requires " + samParams.size());
        }
        List<String> capNames = new ArrayList<>();
        List<Value> capValues = new ArrayList<>();
        Set<String> paramNames = new HashSet<>();
        for (LParam p : lam.params) {
            paramNames.add(p.name());
        }
        for (Map.Entry<String, Value> e : locals.entrySet()) {
            if (paramNames.contains(e.getKey()) || !referencesIdent(lam.body, e.getKey())) {
                continue;
            }
            capNames.add(e.getKey());
            capValues.add(e.getValue());
        }
        return synthInject(ifaceName, sam, samParams, lam.params, lam.body, capNames, capValues);
    }

    /** Desugars a method reference into an equivalent call body, then synthesizes and injects it. */
    private Value materializeMethodRef(MethodRefExpr ref, ReferenceType iface, String ifaceName) {
        InterfaceType it = requireFunctional(iface, ifaceName);
        Method sam = findSam(it);
        List<String> samParams = sam.argumentTypeNames();
        int n = samParams.size();
        List<String> capNames = new ArrayList<>();
        List<Value> capValues = new ArrayList<>();
        String body;
        if (ref.member.equals("new")) {                              // constructor reference
            ClassType ct = requireClassType(ref.recv);
            Method ctor = findByArity(ct.methodsByName("<init>"), n, false, null);
            if (ctor == null) {
                throw err("No constructor-reference target (" + n + " arg(s)): " + ref.recv);
            }
            body = "new " + ref.recv + "(" + argCasts(ctor.argumentTypeNames(), samParams, 0) + ")";
        } else if (localDefs.containsKey(ref.recv) || ref.recv.equals("this")) {   // bound instance
            Value recvVal = ref.recv.equals("this") ? requireThis() : locals.get(ref.recv);
            if (!(recvVal instanceof ObjectReference recvObj)) {
                throw err("Method-reference receiver is not an object: " + ref.recv);
            }
            String capName = ref.recv.equals("this") ? "__self" : ref.recv;
            capNames.add(capName);
            capValues.add(recvVal);
            Method target = findByArity(recvObj.referenceType().methodsByName(ref.member), n, false, Boolean.FALSE);
            if (target == null) {
                throw err("No method-reference target (" + n + " arg(s)): " + ref.recv + "::" + ref.member);
            }
            body = capName + "." + ref.member + "(" + argCasts(target.argumentTypeNames(), samParams, 0) + ")";
        } else {                                                     // static or unbound instance
            ReferenceType rt = loadType(ref.recv);
            if (!(rt instanceof ClassType ct)) {
                throw err("Method-reference receiver class not loaded (FQCN required): " + ref.recv);
            }
            Method stat = findByArity(ct.methodsByName(ref.member), n, true, Boolean.TRUE);
            if (stat != null) {
                body = ref.recv + "." + ref.member + "(" + argCasts(stat.argumentTypeNames(), samParams, 0) + ")";
            } else {
                Method inst = findByArity(ct.methodsByName(ref.member), n - 1, false, Boolean.FALSE);
                if (inst == null) {
                    throw err("No method-reference target: " + ref.recv + "::" + ref.member);
                }
                body = "((" + ref.recv + ") __p0)." + ref.member
                        + "(" + argCasts(inst.argumentTypeNames(), samParams, 1) + ")";
            }
        }
        return synthInject(ifaceName, sam, samParams, List.of(), body, capNames, capValues);
    }

    /** First matching method (arity n, static-ness) among methodsByName candidates. If wantStatic=null, static-ness is ignored. */
    private Method findByArity(List<Method> candidates, int n, boolean allowAbstract, Boolean wantStatic) {
        if (n < 0) {
            return null;
        }
        for (Method m : candidates) {
            if (!allowAbstract && m.isAbstract()) {
                continue;
            }
            if (wantStatic != null && m.isStatic() != wantStatic) {
                continue;
            }
            if (m.argumentTypeNames().size() == n) {
                return m;
            }
        }
        return null;
    }

    /** Argument-list string that casts SAM parameters __p{offset..} to the target method's parameter types. */
    private String argCasts(List<String> targetParams, List<String> samParams, int offset) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targetParams.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String sp = samParams.get(offset + i);
            String p = "__p" + (offset + i);
            if (PRIMITIVE_TYPES.contains(sp)) {
                sb.append(p);   // pass primitive SAM parameters through as-is (widening/match)
            } else {
                String tp = targetParams.get(i);
                String castTo = PRIMITIVE_TYPES.contains(tp) ? wrapperName(tp) : tp;
                sb.append('(').append(castTo).append(") ").append(p);
            }
        }
        return sb.toString();
    }

    private Value synthInject(String ifaceName, Method sam, List<String> samParams,
                              List<LParam> lamParams, String body,
                              List<String> capNames, List<Value> capValues) {
        String fqcn = "protean.synth.Lam_" + LAMBDA_SEQ.incrementAndGet();
        String source = synthesizeSource(fqcn, ifaceName, sam, samParams, lamParams, body, capNames, capValues);
        byte[] bytes = compiler.compileAll(Map.of(fqcn, source)).bytecode().get(fqcn);
        if (bytes == null) {
            throw err("Synthesis compilation failed: " + fqcn);
        }
        ObjectReference instance = injectAndInstantiate(bytes, fqcn);
        ReferenceType lamType = instance.referenceType();
        for (int i = 0; i < capNames.size(); i++) {
            Field f = lamType.fieldByName(capNames.get(i));
            try {
                instance.setValue(f, capValues.get(i));
            } catch (InvalidTypeException | ClassNotLoadedException ex) {
                throw err("Capture injection failed: " + capNames.get(i) + " — " + ex.getMessage());
            }
        }
        return instance;
    }

    private String synthesizeSource(String fqcn, String ifaceName, Method sam, List<String> samParams,
                                    List<LParam> lamParams, String body,
                                    List<String> capNames, List<Value> capValues) {
        String pkg = fqcn.substring(0, fqcn.lastIndexOf('.'));
        String cls = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n");
        sb.append("public class ").append(cls).append(" implements ").append(ifaceName).append(" {\n");
        for (int i = 0; i < capNames.size(); i++) {
            sb.append("  public ").append(captureType(capValues.get(i))).append(' ')
                    .append(capNames.get(i)).append(";\n");
        }
        String ret = sam.returnTypeName();
        sb.append("  public ").append(ret).append(' ').append(sam.name()).append('(');
        for (int i = 0; i < samParams.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(samParams.get(i)).append(" __p").append(i);
        }
        sb.append(") {\n");
        for (int i = 0; i < lamParams.size(); i++) {   // lambda parameter aliases (empty list for method references)
            LParam p = lamParams.get(i);
            if (p.type() != null) {
                sb.append("    ").append(p.type()).append(' ').append(p.name())
                        .append(" = (").append(p.type()).append(") __p").append(i).append(";\n");
            } else {
                sb.append("    var ").append(p.name()).append(" = __p").append(i).append(";\n");
            }
        }
        if (ret.equals("void")) {
            sb.append("    ").append(body).append(";\n");
        } else {
            sb.append("    return (").append(body).append(");\n");
        }
        sb.append("  }\n}\n");
        return sb.toString();
    }

    private String captureType(Value v) {
        if (v == null) {
            return "java.lang.Object";
        }
        if (v instanceof BooleanValue) {
            return "boolean";
        }
        if (v instanceof PrimitiveValue) {
            return v.type().name();
        }
        if (v instanceof StringReference) {
            return "java.lang.String";
        }
        if (v instanceof ObjectReference o) {
            return o.referenceType().name();
        }
        return "java.lang.Object";
    }

    private boolean referencesIdent(String body, String name) {
        java.util.regex.Matcher m = IDENT_TOKEN.matcher(body);
        while (m.find()) {
            if (m.group().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Injects the bytecode into the target VM and creates a no-arg instance (bypasses JDI access control → calls defineClass directly). */
    private ObjectReference injectAndInstantiate(byte[] bytes, String fqcn) {
        try {
            ArrayType byteArray = (ArrayType) vm.classesByName("byte[]").get(0);
            ArrayReference arr = byteArray.newInstance(bytes.length);
            List<Value> bvals = new ArrayList<>(bytes.length);
            for (byte b : bytes) {
                bvals.add(vm.mirrorOf(b));
            }
            arr.setValues(bvals);

            ObjectReference loader = classLoader;
            if (loader == null) {
                ClassType cl = (ClassType) vm.classesByName("java.lang.ClassLoader").get(0);
                loader = (ObjectReference) cl.invokeMethod(thread,
                        cl.methodsByName("getSystemClassLoader").get(0),
                        List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
            }
            Method define = null;
            for (Method m : loader.referenceType().methodsByName("defineClass")) {
                if (m.argumentTypeNames().equals(List.of("java.lang.String", "byte[]", "int", "int"))) {
                    define = m;
                    break;
                }
            }
            if (define == null) {
                throw err("defineClass(String,byte[],int,int) not found");
            }
            Value classObj = loader.invokeMethod(thread, define,
                    List.of(vm.mirrorOf(fqcn), arr, vm.mirrorOf(0), vm.mirrorOf(bytes.length)),
                    ObjectReference.INVOKE_SINGLE_THREADED);
            ClassObjectReference classRef = (ClassObjectReference) classObj;
            // defineClass only defines (not prepared) → Class.newInstance() (prepared) triggers preparation and initialization.
            Method newInst = classRef.referenceType().methodsByName("newInstance").get(0);
            return (ObjectReference) classRef.invokeMethod(thread, newInst, List.of(),
                    ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (InvocationException e) {
            throw err("Target exception while injecting lambda class: " + e.exception().referenceType().name());
        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            throw err("Lambda class injection failed: " + e.getMessage());
        }
    }

    // --- Common utilities ---

    private ObjectReference requireThis() {
        if (thisObj == null) {
            throw err("No this (static context)");
        }
        return thisObj;
    }

    private ClassType requireClassType(String name) {
        ReferenceType rt = tryClass(name);
        if (!(rt instanceof ClassType ct)) {
            throw err("Class not loaded / not instantiable: " + name + " (must be an FQCN loaded in the target VM)");
        }
        return ct;
    }

    private ReferenceType tryClass(String name) {
        List<ReferenceType> types = vm.classesByName(name);
        return types.isEmpty() ? null : types.get(0);
    }

    private PrimitiveValue requireNumeric(Value v) {
        if (v instanceof BooleanValue || !(v instanceof PrimitiveValue p)) {
            throw err("Not a numeric operand: " + typeName(v));
        }
        return p;
    }

    private PrimitiveValue requireIntegral(Value v) {
        if (!(v instanceof PrimitiveValue p) || v instanceof BooleanValue || isFloating(v)) {
            throw err("Not an integral operand (bitwise/shift): " + typeName(v));
        }
        return p;
    }

    private boolean isFloating(Value v) {
        return v instanceof PrimitiveValue && (v.type().name().equals("double") || v.type().name().equals("float"));
    }

    private boolean isLong(Value v) {
        return v instanceof PrimitiveValue && v.type().name().equals("long");
    }

    private boolean boolVal(Value v) {
        if (!(v instanceof BooleanValue b)) {
            throw err("Not a boolean operand: " + typeName(v));
        }
        return b.value();
    }

    private String stringOf(Value v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof StringReference s) {
            return s.value();
        }
        if (v instanceof PrimitiveValue) {
            return v.toString();
        }
        try {
            Value r = invoke(v, "toString", List.of());
            return r instanceof StringReference sr ? sr.value() : String.valueOf(r);
        } catch (RuntimeException e) {
            return v.toString();
        }
    }

    // --- Tokenizer ---

    private Value parseNumber() {
        int start = pos;
        boolean isDouble = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c)) {
                pos++;
            } else if (c == '.') {
                isDouble = true;
                pos++;
            } else {
                break;
            }
        }
        String num = src.substring(start, pos);
        char suffix = pos < src.length() ? src.charAt(pos) : '\0';
        if (suffix == 'L' || suffix == 'l') {
            pos++;
            return vm.mirrorOf(Long.parseLong(num));
        }
        if (suffix == 'd' || suffix == 'D' || suffix == 'f' || suffix == 'F') {
            pos++;
            return vm.mirrorOf(Double.parseDouble(num));
        }
        return isDouble ? vm.mirrorOf(Double.parseDouble(num)) : vm.mirrorOf(Integer.parseInt(num));
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c == '\\' && pos < src.length() ? unescape(src.charAt(pos++)) : c);
        }
        throw err("Unterminated string");
    }

    private char parseChar() {
        expect('\'');
        if (pos >= src.length()) {
            throw err("Unterminated char literal");
        }
        char c = src.charAt(pos++);
        if (c == '\\' && pos < src.length()) {
            c = unescape(src.charAt(pos++));
        }
        expect('\'');
        return c;
    }

    private char unescape(char e) {
        return switch (e) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '0' -> '\0';
            case '"' -> '"';
            case '\'' -> '\'';
            case '\\' -> '\\';
            default -> e;
        };
    }

    /** Dot-joined name (type name / class path). */
    private String parseDottedName() {
        StringBuilder sb = new StringBuilder(parseIdent());
        while (true) {
            skipWs();
            if (peek() == '.') {
                int save = pos;
                pos++;
                skipWs();
                if (!isIdentStart(peek())) {
                    pos = save;
                    break;
                }
                sb.append('.').append(parseIdent());
            } else {
                break;
            }
        }
        return sb.toString();
    }

    /** Skips generic type arguments {@code <...>} in a balanced way (JDI uses erased types → ignore). */
    private void skipGenerics() {
        skipWs();
        if (peek() != '<') {
            return;
        }
        int depth = 0;
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                if (--depth == 0) {
                    return;
                }
            }
        }
        throw err("Unterminated generic '<...>'");
    }

    private String parseIdent() {
        skipWs();
        int start = pos;
        if (pos >= src.length() || !isIdentStart(src.charAt(pos))) {
            throw err("Expected an identifier");
        }
        pos++;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        return src.substring(start, pos);
    }

    /** Consumes the keyword and returns true if it appears as a standalone token (must not be followed by an identifier character). */
    private boolean matchKeyword(String kw) {
        skipWs();
        if (src.regionMatches(pos, kw, 0, kw.length())) {
            int after = pos + kw.length();
            if (after >= src.length() || !isIdentPart(src.charAt(after))) {
                pos = after;
                return true;
            }
        }
        return false;
    }

    /** Longest operator token at the current position (does not consume). null if none. */
    private String peekOp() {
        skipWs();
        for (String op : OPS) {
            if (src.regionMatches(pos, op, 0, op.length())) {
                return op;
            }
        }
        return null;
    }

    private void consumeOp(String op) {
        skipWs();
        pos += op.length();
    }

    private boolean isAssignOp(String op) {
        return switch (op) {
            case "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=" -> true;
            default -> false;
        };
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        skipWs();
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void expect(char c) {
        skipWs();
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw err("Expected '" + c + "'");
        }
        pos++;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("Expression evaluation error: " + msg);
    }
}
