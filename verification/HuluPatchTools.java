import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.Adaptors.MethodDefinition;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Package a Hulu-only bundle, or audit a local application of it without installing it. */
public final class HuluPatchTools {
    private static final String PRESENTER = "Lcom/hulu/features/playback/PlayerPresenter;";
    private static final String PATCH_PATH = "dev/chudpresence/patches/hulu/";
    private static final String EXTENSION = "extensions/metadata-extension.rve";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("package INPUT.rvp OUTPUT.rvp | audit STOCK.apk PATCHED.apk");
        if (args[0].equals("package")) packageBundle(Path.of(args[1]), Path.of(args[2]));
        else if (args[0].equals("audit")) audit(Path.of(args[1]), Path.of(args[2]));
        else throw new IllegalArgumentException("Unknown operation");
    }

    private static byte[] subset(byte[] bytes, String prefix) throws IOException {
        var dex = new DexBackedDexFile(Opcodes.getDefault(), bytes);
        var available = new HashMap<String, ClassDef>();
        for (ClassDef type : dex.getClasses()) available.put(type.getType(), type);
        var selected = new LinkedHashMap<String, ClassDef>();
        var pending = new ArrayDeque<String>();
        for (String name : available.keySet()) if (name.startsWith(prefix)) pending.add(name);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            ClassDef type = available.get(name);
            if (type == null || selected.putIfAbsent(name, type) != null) continue;
            // D8 can share API outlines with another source class. Keep their transitive
            // dependencies even when their generated names don't start with "Hulu".
            for (String dependency : dependencies(type)) {
                if (available.containsKey(dependency) && !selected.containsKey(dependency)) pending.add(dependency);
            }
        }
        if (selected.isEmpty()) throw new IllegalStateException("No classes for " + prefix);
        var store = new MemoryDataStore();
        DexPool.writeTo(store, new ImmutableDexFile(dex.getOpcodes(), selected.values()));
        System.out.println("Packaged " + selected.size() + " classes for " + prefix);
        return store.getData();
    }

    private static Set<String> dependencies(ClassDef type) {
        var result = new HashSet<String>();
        if (type.getSuperclass() != null) result.add(type.getSuperclass());
        result.addAll(type.getInterfaces());
        for (var field : type.getFields()) descriptorTypes(field.getType(), result);
        for (Method method : type.getMethods()) {
            descriptorTypes(method.getReturnType(), result);
            for (CharSequence parameter : method.getParameterTypes()) descriptorTypes(parameter.toString(), result);
            if (method.getImplementation() == null) continue;
            for (var instruction : method.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction reference) descriptorTypes(reference.getReference().toString(), result);
                if (instruction instanceof DualReferenceInstruction reference) descriptorTypes(reference.getReference2().toString(), result);
            }
            for (var block : method.getImplementation().getTryBlocks()) {
                for (var handler : block.getExceptionHandlers()) {
                    if (handler.getExceptionType() != null) result.add(handler.getExceptionType());
                }
            }
        }
        return result;
    }

    private static void descriptorTypes(String descriptor, Set<String> result) {
        var matcher = java.util.regex.Pattern.compile("L[A-Za-z0-9_/$-]+;").matcher(descriptor);
        while (matcher.find()) result.add(matcher.group());
    }

    private static void packageBundle(Path input, Path output) throws Exception {
        if (input.toAbsolutePath().equals(output.toAbsolutePath())) throw new IllegalArgumentException("Output must differ from input");
        try (var zip = new ZipFile(input.toFile())) {
            Manifest manifest = new Manifest(zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")));
            manifest.getMainAttributes().putValue("Name", "ChudPresence Hulu metadata");
            manifest.getMainAttributes().putValue("Description", "Hulu-only descriptive media-session metadata patch");
            try (var out = new JarOutputStream(Files.newOutputStream(output), manifest)) {
                for (var entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !(name.startsWith(PATCH_PATH) && name.endsWith(".class"))) continue;
                    write(out, name, zip.getInputStream(entry).readAllBytes());
                }
                write(out, "classes.dex", subset(zip.getInputStream(zip.getEntry("classes.dex")).readAllBytes(), "L" + PATCH_PATH));
                write(out, EXTENSION, subset(zip.getInputStream(zip.getEntry(EXTENSION)).readAllBytes(), "Ldev/chudpresence/revanced/Hulu"));
            }
        }
        System.out.println("Saved " + output.toAbsolutePath());
    }

    private static void write(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static Map<String, ClassDef> classes(Path apk) throws Exception {
        var result = new TreeMap<String, ClassDef>();
        var dexes = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.getDefault());
        for (String entry : dexes.getDexEntryNames()) {
            for (ClassDef type : dexes.getEntry(entry).getDexFile().getClasses()) result.put(type.getType(), type);
        }
        return result;
    }

    private static ClassDefinition definition(ClassDef type) {
        var options = new BaksmaliOptions();
        options.debugInfo = false;
        options.accessorComments = false;
        options.sequentialLabels = true;
        return new ClassDefinition(options, type);
    }

    private static String render(ClassDef type) throws Exception {
        var text = new StringWriter();
        var out = new BaksmaliWriter(text);
        var definition = definition(type);
        definition.writeTo(out);
        out.flush();
        if (definition.hadValidationErrors()) throw new IllegalStateException("Invalid bytecode: " + type.getType());
        // The DEX writer can elide explicit default field values without changing behavior.
        String normalized = text.toString().replace("const-string/jumbo", "const-string")
            .replace("goto/16 ", "goto ").replace("goto/32 ", "goto ")
            .replaceAll("(?m)^(\\.field[^\\r\\n]*:[ZBSCIJFD]) = (?:false|0x0[Lts]?|0\\.0[fD]?|'\\\\u0000')$", "$1")
            .replaceAll("(?m)^(\\.field[^\\r\\n]*:(?:L[^;\\r\\n]+;|\\[[^\\r\\n ]+)) = null$", "$1");
        // String index widths can also change branch widths and switch-payload alignment nops.
        return normalized.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.equals("nop"))
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static List<String> methodLines(ClassDef type, Method method) throws Exception {
        var text = new StringWriter();
        var out = new BaksmaliWriter(text);
        var definition = definition(type);
        if (method.getImplementation() == null) MethodDefinition.writeEmptyMethodTo(out, method, definition);
        else new MethodDefinition(definition, method, method.getImplementation()).writeTo(out);
        out.flush();
        return new ArrayList<>(text.toString().replace("const-string/jumbo", "const-string").lines()
            .map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#") && !s.equals("nop")).toList());
    }

    private static void audit(Path stockPath, Path patchedPath) throws Exception {
        var stock = classes(stockPath);
        var patched = classes(patchedPath);
        int unchanged = 0, changed = 0, added = 0;
        for (var entry : stock.entrySet()) {
            ClassDef after = patched.get(entry.getKey());
            if (after == null) throw new IllegalStateException("Removed class: " + entry.getKey());
            if (render(entry.getValue()).equals(render(after))) { unchanged++; continue; }
            if (!entry.getKey().equals(PRESENTER)) {
                Files.writeString(patchedPath.resolveSibling("hulu-audit-before.smali"), render(entry.getValue()));
                Files.writeString(patchedPath.resolveSibling("hulu-audit-after.smali"), render(after));
                throw new IllegalStateException("Unexpected modified class: " + entry.getKey());
            }
            changed++;
            var afterMethods = new HashMap<String, Method>();
            for (Method method : after.getMethods()) afterMethods.put(method.toString(), method);
            int changedMethods = 0;
            for (Method method : entry.getValue().getMethods()) {
                Method modified = afterMethods.remove(method.toString());
                if (modified == null) throw new IllegalStateException("Removed method: " + method);
                List<String> beforeLines = methodLines(entry.getValue(), method);
                List<String> afterLines = methodLines(after, modified);
                if (beforeLines.equals(afterLines)) continue;
                int prefix = switch (method.toString()) {
                    case PRESENTER + "->s(Ljava/lang/String;Z)V" -> 6;
                    case PRESENTER + "->s0(Lcom/hulu/browse/model/entity/PlayableEntity;)V" -> 5;
                    default -> throw new IllegalStateException("Unexpected modified method: " + method);
                };
                int first = afterLines.indexOf("move-object/from16 v0, p0");
                if (first < 0 || !afterLines.get(first + prefix - 1).contains("HuluMetadataBridge;->publish"))
                    throw new IllegalStateException("Unexpected hook prefix: " + method);
                afterLines.subList(first, first + prefix).clear();
                if (!beforeLines.equals(afterLines)) throw new IllegalStateException("Original method body changed: " + method);
                System.out.println("Verified additive metadata hook: " + method);
                changedMethods++;
            }
            if (!afterMethods.isEmpty() || changedMethods != 2) throw new IllegalStateException("Unexpected method delta");
        }
        for (String type : patched.keySet()) {
            if (stock.containsKey(type)) continue;
            if (!type.startsWith("Ldev/chudpresence/revanced/Hulu") &&
                !type.matches("Ldev/chudpresence/revanced/MetadataEnricher\\$\\$ExternalSyntheticApiModelOutline[0-9]+;"))
                throw new IllegalStateException("Unexpected added class: " + type);
            added++;
        }
        if (changed != 1 || added < 2) throw new IllegalStateException("Unexpected class delta");
        try (var stockZip = new ZipFile(stockPath.toFile()); var patchedZip = new ZipFile(patchedPath.toFile())) {
            var names = new TreeSet<String>();
            stockZip.stream().map(ZipEntry::getName).forEach(names::add);
            patchedZip.stream().map(ZipEntry::getName).forEach(names::add);
            for (String name : names) {
                if (name.startsWith("META-INF/") || name.endsWith(".dex")) continue;
                var before = stockZip.getEntry(name);
                var after = patchedZip.getEntry(name);
                if (before == null || after == null || !Arrays.equals(stockZip.getInputStream(before).readAllBytes(), patchedZip.getInputStream(after).readAllBytes()))
                    throw new IllegalStateException("Unexpected non-DEX change: " + name);
            }
        }
        System.out.println("PASS: " + unchanged + " stock classes unchanged; one presenter changed by two additive hooks; " + added + " Hulu extension/helper classes added.");
        System.out.println("PASS: All non-DEX/non-signature APK entries, including native libraries and resources, are byte-identical.");
    }
}
