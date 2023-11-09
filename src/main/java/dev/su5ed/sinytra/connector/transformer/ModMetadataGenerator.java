package dev.su5ed.sinytra.connector.transformer;

import com.google.gson.JsonObject;
import dev.su5ed.sinytra.connector.ConnectorUtil;
import net.minecraftforge.fart.api.Transformer;
import org.apache.commons.lang3.RandomStringUtils;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ModMetadataGenerator implements Transformer {
    public static final String RESOURCE = "pack.mcmeta";
    public static final int RESOURCE_PACK_FORMAT = 15;
    private static final String MOD_ANNOTATION_DESC = "Lnet/minecraftforge/fml/common/Mod;";

    private final String modid;
    private boolean seen;

    public ModMetadataGenerator(String modid) {
        this.modid = modid;
    }

    @Override
    public ResourceEntry process(ResourceEntry entry) {
        if (RESOURCE.equals(entry.getName())) {
            this.seen = true;
        }
        return entry;
    }

    @Override
    public Collection<? extends Entry> getExtras() {
        List<Entry> extras = new ArrayList<>();

        // Generate FML mod class
        // Include a random string for uniqueness, just in case
        String className = "dev/su5ed/sinytra/generated/%s_%s/Entrypoint_%s".formatted(this.modid, RandomStringUtils.randomAlphabetic(5), this.modid);
        byte[] classData = generateFMLModEntrypoint(className);
        extras.add(ClassEntry.create(className + ".class", ConnectorUtil.ZIP_TIME, classData));

        // Generate pack metadata
        if (!this.seen) {
            byte[] data = generatePackMetadataFile(this.modid);
            extras.add(ResourceEntry.create(RESOURCE, ConnectorUtil.ZIP_TIME, data));
        }
        return extras;
    }

    private byte[] generateFMLModEntrypoint(String className) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

        // Annotate the class with FML's @Mod annotation
        AnnotationVisitor modAnnotation = cw.visitAnnotation(MOD_ANNOTATION_DESC, true);
        modAnnotation.visit("value", this.modid);
        modAnnotation.visitEnd();

        // Add a default constructor to the class
        MethodVisitor constructor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        Label start = new Label();
        constructor.visitLabel(start);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        Label end = new Label();
        constructor.visitLabel(end);
        constructor.visitLocalVariable("this", "L" + className + ";", null, start, end, 0);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        cw.visitEnd();

        return cw.toByteArray();
    }

    public static byte[] generatePackMetadataFile(String modid) {
        JsonObject packMeta = new JsonObject();
        JsonObject pack = new JsonObject();
        JsonObject description = new JsonObject();
        description.addProperty("text", modid + " resources, generated by Connector");
        pack.add("description", description);
        pack.addProperty("pack_format", RESOURCE_PACK_FORMAT);
        packMeta.add("pack", pack);

        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
            try (Writer writer = new OutputStreamWriter(byteStream)) {
                ConnectorUtil.prettyGson().toJson(packMeta, writer);
                writer.flush();
            }
            byte[] data = byteStream.toByteArray();
            return data;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
