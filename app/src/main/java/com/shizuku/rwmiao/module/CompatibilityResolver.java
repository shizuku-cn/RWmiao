package com.shizuku.rwmiao.module;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves canonical and source-remapped Rusted Warfare 1.15 symbols without
 * binding compatibility to an APK package name:
 *
 * <ul>
 *     <li>the original short symbols ({@code k}, {@code ce}, {@code bP});</li>
 *     <li>source-remapped symbols ({@code GameEngine}, {@code class_908},
 *     {@code field_5209}, {@code method_2365}).</li>
 * </ul>
 *
 * The bundled profile contains canonical class order and member signatures.
 * Member numbers are deliberately not stored.  They are aligned at runtime by
 * descriptor and declaration order, so repackaging and harmless source rebuilds
 * do not require another package-specific table.
 */
final class CompatibilityResolver {
    private static final String CANONICAL_PREFIX = "com.corrodinggames.rts";
    private static final Pattern NUMBERED_FIELD = Pattern.compile("field_(\\d+)");
    private static final Pattern NUMBERED_METHOD = Pattern.compile("method_(\\d+)");

    private final String targetPrefix;
    private final Map<String, ClassSymbol> classes = new LinkedHashMap<>();
    private final Map<String, String> actualToCanonical = new HashMap<>();
    private final Map<Class<?>, Map<String, Field>> alignedFields = new HashMap<>();
    private final Map<Class<?>, Map<String, List<Method>>> alignedMethods = new HashMap<>();

    private CompatibilityResolver(String targetPrefix) {
        this.targetPrefix = targetPrefix;
    }

    static CompatibilityResolver load(RWmiaoModule host, String targetPrefix,
                                      Set<String> dexClasses) {
        CompatibilityResolver resolver = new CompatibilityResolver(targetPrefix);
        try (InputStream input = host.openModuleAsset("rwmiao-symbols.map");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            ClassSymbol current = null;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] parts = line.split("\\t", -1);
                if (parts.length < 3) continue;
                switch (parts[0]) {
                    case "C":
                        current = new ClassSymbol(parts[1], parts[2]);
                        resolver.classes.put(current.canonicalName, current);
                        break;
                    case "F":
                        if (current != null && parts.length >= 5
                                && current.canonicalName.equals(parts[1])) {
                            current.fields.add(new MemberSymbol(
                                    parts[2], parts[3], "1".equals(parts[4])));
                        }
                        break;
                    case "M":
                        if (current != null && parts.length >= 5
                                && current.canonicalName.equals(parts[1])) {
                            current.methods.add(new MemberSymbol(
                                    parts[2], parts[3], "1".equals(parts[4])));
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable t) {
            host.log(6, "RWmiao", "Unable to load compatibility symbol profile", t);
            return resolver;
        }

        for (ClassSymbol symbol : resolver.classes.values()) {
            String direct = targetPrefix + "." + symbol.canonicalName;
            String intermediary = targetPrefix + "." + symbol.intermediaryName;
            if (dexClasses.contains(direct)) {
                symbol.actualName = direct;
            } else if (dexClasses.contains(intermediary)) {
                symbol.actualName = intermediary;
            }
            if (symbol.actualName != null) {
                resolver.actualToCanonical.put(symbol.actualName, symbol.canonicalName);
            }
        }
        return resolver;
    }

    String className(String canonicalSuffix) {
        ClassSymbol symbol = classes.get(canonicalSuffix);
        return symbol != null && symbol.actualName != null
                ? symbol.actualName : targetPrefix + "." + canonicalSuffix;
    }

    Field field(Class<?> type, String canonicalName) {
        if (type == null) return null;
        Map<String, Field> mapping;
        synchronized (alignedFields) {
            mapping = alignedFields.get(type);
            if (mapping == null) {
                mapping = alignFields(type);
                alignedFields.put(type, mapping);
            }
        }
        return mapping.get(canonicalName);
    }

    List<Method> methods(Class<?> type, String canonicalName) {
        if (type == null) return Collections.emptyList();
        Map<String, List<Method>> mapping;
        synchronized (alignedMethods) {
            mapping = alignedMethods.get(type);
            if (mapping == null) {
                mapping = alignMethods(type);
                alignedMethods.put(type, mapping);
            }
        }
        List<Method> result = mapping.get(canonicalName);
        return result == null ? Collections.emptyList() : result;
    }

    private Map<String, Field> alignFields(Class<?> type) {
        ClassSymbol symbol = symbol(type);
        if (symbol == null || symbol.fields.isEmpty()) return Collections.emptyMap();
        ArrayList<Field> declared = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            declared.add(field);
        }
        HashMap<String, Field> result = new HashMap<>();
        boolean[] matchedExpected = new boolean[symbol.fields.size()];
        addDirectFieldMatches(symbol.fields, declared, matchedExpected, result);
        addSemanticFieldAliases(symbol, declared, matchedExpected, result);

        ArrayList<Field> actual = new ArrayList<>();
        for (Field field : declared) {
            if (number(NUMBERED_FIELD, field.getName()) >= 0 && !result.containsValue(field)) {
                actual.add(field);
            }
        }
        actual.sort(Comparator.comparingInt(field -> number(NUMBERED_FIELD, field.getName())));
        List<int[]> pairs = alignRemaining(symbol.fields, matchedExpected, actual,
                (expected, field) ->
                        expected.isStatic == Modifier.isStatic(field.getModifiers())
                                && expected.descriptor.equals(descriptor(field.getType())));
        for (int[] pair : pairs) {
            MemberSymbol expected = symbol.fields.get(pair[0]);
            Field field = actual.get(pair[1]);
            if (matchedExpected[pair[0]] || result.containsValue(field)) continue;
            field.setAccessible(true);
            result.putIfAbsent(expected.name, field);
            matchedExpected[pair[0]] = true;
        }
        ArrayList<Field> semantic = new ArrayList<>();
        for (Field field : declared) {
            if (number(NUMBERED_FIELD, field.getName()) < 0) semantic.add(field);
        }
        addGapFieldFallbacks(symbol.fields, matchedExpected, actual, pairs, semantic, result);
        addUniqueFieldFallbacks(symbol.fields, matchedExpected, declared, result);
        return result;
    }

    private Map<String, List<Method>> alignMethods(Class<?> type) {
        ClassSymbol symbol = symbol(type);
        if (symbol == null || symbol.methods.isEmpty()) return Collections.emptyMap();
        ArrayList<Method> declared = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            declared.add(method);
        }
        HashMap<String, List<Method>> result = new HashMap<>();
        boolean[] matchedExpected = new boolean[symbol.methods.size()];
        addDirectMethodMatches(symbol.methods, declared, matchedExpected, result);
        addInheritedOverrideMatches(type, symbol.methods, declared, matchedExpected, result);

        ArrayList<Method> actual = new ArrayList<>();
        for (Method method : declared) {
            if (number(NUMBERED_METHOD, method.getName()) >= 0
                    && !containsMethod(result, method)) actual.add(method);
        }
        actual.sort(Comparator.comparingInt(method -> number(NUMBERED_METHOD, method.getName())));
        List<int[]> pairs = alignRemaining(symbol.methods, matchedExpected, actual,
                (expected, method) ->
                        expected.isStatic == Modifier.isStatic(method.getModifiers())
                                && expected.descriptor.equals(descriptor(method)));
        for (int[] pair : pairs) {
            MemberSymbol expected = symbol.methods.get(pair[0]);
            Method method = actual.get(pair[1]);
            if (matchedExpected[pair[0]] || containsMethod(result, method)) continue;
            method.setAccessible(true);
            result.computeIfAbsent(expected.name, unused -> new ArrayList<>()).add(method);
            matchedExpected[pair[0]] = true;
        }
        addUniqueMethodFallbacks(symbol.methods, matchedExpected, declared, result);
        return result;
    }

    /**
     * A source-remapped build can retain descriptive field names instead of the
     * original short names.  These aliases describe engine semantics, not an
     * APK or package, and are only accepted when type and staticness still match
     * the profile.  The common position aliases are particularly important
     * because three adjacent float fields cannot be distinguished by signature
     * alone.
     */
    private void addSemanticFieldAliases(ClassSymbol owner, List<Field> actual,
                                         boolean[] matched, Map<String, Field> result) {
        for (int index = 0; index < owner.fields.size(); index++) {
            if (matched[index]) continue;
            MemberSymbol wanted = owner.fields.get(index);
            String alias = semanticFieldAlias(owner.canonicalName, wanted.name);
            if (alias == null) continue;
            for (Field field : actual) {
                if (!alias.equals(field.getName()) || result.containsValue(field)
                        || wanted.isStatic != Modifier.isStatic(field.getModifiers())
                        || !wanted.descriptor.equals(descriptor(field.getType()))) continue;
                field.setAccessible(true);
                result.putIfAbsent(wanted.name, field);
                matched[index] = true;
                break;
            }
        }
    }

    private static String semanticFieldAlias(String owner, String field) {
        if ("gameFramework.ah".equals(owner)) {
            if ("eq".equals(field)) return "x";
            if ("er".equals(field)) return "y";
            if ("es".equals(field)) return "height";
        }
        if ("game.units.ce".equals(owner) && "bZ".equals(field)) return "player";
        if ("game.units.bp".equals(owner)) {
            if ("O".equals(field)) return "waypointCount";
            if ("Q".equals(field)) return "waypoints";
        }
        if ("game.units.en".equals(owner)) {
            if ("e".equals(field)) return "x";
            if ("f".equals(field)) return "y";
        }
        if ("gameFramework.k".equals(owner) && "bp".equals(field)) return "userPlayer";
        return null;
    }

    /**
     * Source-remappers keep one method name for an override chain.  Resolve
     * those overrides from the already mapped parent contract before aligning
     * class-local numbered methods; otherwise a low global override number can
     * shift every same-signature method in the subclass.
     */
    private void addInheritedOverrideMatches(Class<?> type, List<MemberSymbol> expected,
                                              List<Method> declared, boolean[] matched,
                                              Map<String, List<Method>> result) {
        ArrayList<Class<?>> parents = new ArrayList<>();
        if (type.getSuperclass() != null) parents.add(type.getSuperclass());
        Collections.addAll(parents, type.getInterfaces());
        for (int index = 0; index < expected.size(); index++) {
            if (matched[index]) continue;
            MemberSymbol wanted = expected.get(index);
            for (Class<?> parent : parents) {
                for (Method parentMethod : methods(parent, wanted.name)) {
                    if (wanted.isStatic != Modifier.isStatic(parentMethod.getModifiers())
                            || !wanted.descriptor.equals(descriptor(parentMethod))) continue;
                    for (Method method : declared) {
                        if (!method.getName().equals(parentMethod.getName())
                                || containsMethod(result, method)
                                || wanted.isStatic != Modifier.isStatic(method.getModifiers())
                                || !wanted.descriptor.equals(descriptor(method))) continue;
                        method.setAccessible(true);
                        result.computeIfAbsent(wanted.name, unused -> new ArrayList<>()).add(method);
                        matched[index] = true;
                        break;
                    }
                    if (matched[index]) break;
                }
                if (matched[index]) break;
            }
        }
    }

    private void addDirectFieldMatches(List<MemberSymbol> expected, List<Field> actual,
                                       boolean[] matched, Map<String, Field> result) {
        for (int index = 0; index < expected.size(); index++) {
            MemberSymbol wanted = expected.get(index);
            for (Field field : actual) {
                if (!wanted.name.equals(field.getName())
                        || wanted.isStatic != Modifier.isStatic(field.getModifiers())
                        || !wanted.descriptor.equals(descriptor(field.getType()))) continue;
                field.setAccessible(true);
                result.putIfAbsent(wanted.name, field);
                matched[index] = true;
                break;
            }
        }
    }

    private void addDirectMethodMatches(List<MemberSymbol> expected, List<Method> actual,
                                        boolean[] matched, Map<String, List<Method>> result) {
        for (int index = 0; index < expected.size(); index++) {
            MemberSymbol wanted = expected.get(index);
            for (Method method : actual) {
                if (!wanted.name.equals(method.getName())
                        || wanted.isStatic != Modifier.isStatic(method.getModifiers())
                        || !wanted.descriptor.equals(descriptor(method))) continue;
                method.setAccessible(true);
                result.computeIfAbsent(wanted.name, unused -> new ArrayList<>()).add(method);
                matched[index] = true;
                break;
            }
        }
    }

    /**
     * Source-remapped builds sometimes replace a short field name with a semantic
     * name.  The numeric aliases on both sides retain a hole for that field.  A
     * hole bracketed by two aligned members gives us a safe, package-independent
     * way to recover the semantic field even when several canonical fields share
     * the same primitive type.
     */
    private void addGapFieldFallbacks(List<MemberSymbol> expected, boolean[] matched,
                                      List<Field> numbered, List<int[]> pairs,
                                      List<Field> semantic, Map<String, Field> result) {
        HashMap<Integer, List<Field>> candidatesByExpected = new HashMap<>();
        HashMap<Field, Integer> candidateFrequency = new HashMap<>();
        java.util.HashSet<Integer> usedNumbers = new java.util.HashSet<>();
        for (Field field : numbered) usedNumbers.add(number(NUMBERED_FIELD, field.getName()));

        for (int index = 0; index < expected.size(); index++) {
            if (matched[index]) continue;
            int[] left = null;
            int[] right = null;
            for (int[] pair : pairs) {
                if (pair[0] < index) left = pair;
                if (pair[0] > index) {
                    right = pair;
                    break;
                }
            }
            if (left == null || right == null) continue;
            int leftNumber = number(NUMBERED_FIELD, numbered.get(left[1]).getName());
            int rightNumber = number(NUMBERED_FIELD, numbered.get(right[1]).getName());
            int projectedLeft = leftNumber + index - left[0];
            int projectedRight = rightNumber - (right[0] - index);
            if (projectedLeft != projectedRight || usedNumbers.contains(projectedLeft)) continue;

            MemberSymbol wanted = expected.get(index);
            ArrayList<Field> candidates = new ArrayList<>();
            for (Field field : semantic) {
                if (result.containsValue(field)
                        || wanted.isStatic != Modifier.isStatic(field.getModifiers())
                        || !wanted.descriptor.equals(descriptor(field.getType()))) continue;
                candidates.add(field);
                candidateFrequency.put(field, candidateFrequency.getOrDefault(field, 0) + 1);
            }
            if (!candidates.isEmpty()) candidatesByExpected.put(index, candidates);
        }

        for (Map.Entry<Integer, List<Field>> entry : candidatesByExpected.entrySet()) {
            Field unique = null;
            for (Field field : entry.getValue()) {
                if (candidateFrequency.getOrDefault(field, 0) != 1) continue;
                if (unique != null) {
                    unique = null;
                    break;
                }
                unique = field;
            }
            if (unique == null || result.containsValue(unique)) continue;
            int index = entry.getKey();
            unique.setAccessible(true);
            result.putIfAbsent(expected.get(index).name, unique);
            matched[index] = true;
        }
    }

    private void addUniqueFieldFallbacks(List<MemberSymbol> expected, boolean[] matched,
                                         List<Field> semantic, Map<String, Field> result) {
        for (int index = 0; index < expected.size(); index++) {
            if (matched[index]) continue;
            MemberSymbol wanted = expected.get(index);
            if (unmatchedSignatureCount(expected, matched, wanted) != 1) continue;
            Field candidate = null;
            for (Field field : semantic) {
                if (result.containsValue(field)) continue;
                if (wanted.isStatic != Modifier.isStatic(field.getModifiers())
                        || !wanted.descriptor.equals(descriptor(field.getType()))) continue;
                if (candidate != null) {
                    candidate = null;
                    break;
                }
                candidate = field;
            }
            if (candidate != null) {
                candidate.setAccessible(true);
                result.putIfAbsent(wanted.name, candidate);
                matched[index] = true;
            }
        }
    }

    private void addUniqueMethodFallbacks(List<MemberSymbol> expected, boolean[] matched,
                                          List<Method> semantic,
                                          Map<String, List<Method>> result) {
        for (int index = 0; index < expected.size(); index++) {
            if (matched[index]) continue;
            MemberSymbol wanted = expected.get(index);
            if (unmatchedSignatureCount(expected, matched, wanted) != 1) continue;
            Method candidate = null;
            for (Method method : semantic) {
                if (containsMethod(result, method)) continue;
                if (wanted.isStatic != Modifier.isStatic(method.getModifiers())
                        || !wanted.descriptor.equals(descriptor(method))) continue;
                if (candidate != null) {
                    candidate = null;
                    break;
                }
                candidate = method;
            }
            if (candidate != null) {
                candidate.setAccessible(true);
                result.computeIfAbsent(wanted.name, unused -> new ArrayList<>()).add(candidate);
                matched[index] = true;
            }
        }
    }

    private static boolean containsMethod(Map<String, List<Method>> result, Method candidate) {
        for (List<Method> methods : result.values()) {
            if (methods.contains(candidate)) return true;
        }
        return false;
    }

    private static int unmatchedSignatureCount(List<MemberSymbol> symbols,
                                               boolean[] matched, MemberSymbol wanted) {
        int count = 0;
        for (int index = 0; index < symbols.size(); index++) {
            MemberSymbol symbol = symbols.get(index);
            if (!matched[index] && symbol.isStatic == wanted.isStatic
                    && symbol.descriptor.equals(wanted.descriptor)) count++;
        }
        return count;
    }

    private ClassSymbol symbol(Class<?> type) {
        String canonical = actualToCanonical.get(type.getName());
        if (canonical == null && type.getName().startsWith(targetPrefix + ".")) {
            String suffix = type.getName().substring(targetPrefix.length() + 1);
            if (classes.containsKey(suffix)) canonical = suffix;
        }
        return canonical == null ? null : classes.get(canonical);
    }

    private String descriptor(Method method) {
        StringBuilder result = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) {
            result.append(descriptor(parameter));
        }
        return result.append(')').append(descriptor(method.getReturnType())).toString();
    }

    private String descriptor(Class<?> type) {
        if (type.isArray()) return "[" + descriptor(type.getComponentType());
        if (type.isPrimitive()) {
            if (type == void.class) return "V";
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == int.class) return "I";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            if (type == double.class) return "D";
        }
        String name = type.getName();
        String canonical = actualToCanonical.get(name);
        if (canonical != null) {
            name = CANONICAL_PREFIX + "." + canonical;
        } else if (name.startsWith(targetPrefix + ".")) {
            name = CANONICAL_PREFIX + name.substring(targetPrefix.length());
        }
        return "L" + name.replace('.', '/') + ";";
    }

    private static int number(Pattern pattern, String name) {
        Matcher matcher = pattern.matcher(name);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static <A> List<int[]> alignRemaining(List<MemberSymbol> expected,
                                                   boolean[] matched,
                                                   List<A> actual,
                                                   Matcher2<MemberSymbol, A> matcher) {
        ArrayList<MemberSymbol> remaining = new ArrayList<>();
        ArrayList<Integer> originalIndexes = new ArrayList<>();
        for (int index = 0; index < expected.size(); index++) {
            if (matched[index]) continue;
            remaining.add(expected.get(index));
            originalIndexes.add(index);
        }
        List<int[]> localPairs = align(remaining, actual, matcher);
        ArrayList<int[]> result = new ArrayList<>(localPairs.size());
        for (int[] pair : localPairs) {
            result.add(new int[]{originalIndexes.get(pair[0]), pair[1]});
        }
        return result;
    }

    private static <E, A> List<int[]> align(List<E> expected, List<A> actual,
                                             Matcher2<E, A> matcher) {
        int rows = expected.size();
        int columns = actual.size();
        int[][] score = new int[rows + 1][columns + 1];
        for (int row = rows - 1; row >= 0; row--) {
            for (int column = columns - 1; column >= 0; column--) {
                score[row][column] = matcher.matches(expected.get(row), actual.get(column))
                        ? 1 + score[row + 1][column + 1]
                        : Math.max(score[row + 1][column], score[row][column + 1]);
            }
        }
        ArrayList<int[]> result = new ArrayList<>();
        int row = 0;
        int column = 0;
        while (row < rows && column < columns) {
            if (matcher.matches(expected.get(row), actual.get(column))
                    && score[row][column] == 1 + score[row + 1][column + 1]) {
                result.add(new int[]{row++, column++});
            } else if (score[row + 1][column] >= score[row][column + 1]) {
                row++;
            } else {
                column++;
            }
        }
        return result;
    }

    private static final class ClassSymbol {
        final String canonicalName;
        final String intermediaryName;
        final ArrayList<MemberSymbol> fields = new ArrayList<>();
        final ArrayList<MemberSymbol> methods = new ArrayList<>();
        String actualName;

        ClassSymbol(String canonicalName, String intermediaryName) {
            this.canonicalName = canonicalName;
            this.intermediaryName = intermediaryName;
        }
    }

    private static final class MemberSymbol {
        final String name;
        final String descriptor;
        final boolean isStatic;

        MemberSymbol(String name, String descriptor, boolean isStatic) {
            this.name = name;
            this.descriptor = descriptor;
            this.isStatic = isStatic;
        }
    }

    @FunctionalInterface
    private interface Matcher2<E, A> {
        boolean matches(E expected, A actual);
    }
}
