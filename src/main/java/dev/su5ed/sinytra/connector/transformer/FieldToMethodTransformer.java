package dev.su5ed.sinytra.connector.transformer;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerVisitor;
import net.fabricmc.accesswidener.AccessWidenerWriter;
import net.fabricmc.accesswidener.ForwardingVisitor;
import net.minecraftforge.coremod.api.ASMAPI;
import net.minecraftforge.fart.api.Transformer;
import net.minecraftforge.fart.internal.EnhancedRemapper;
import net.minecraftforge.srgutils.IMappingFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;

public class FieldToMethodTransformer implements Transformer {
    // Extracted from forge's coremods/field_to_method.js
    private static final Map<String, Map<String, String>> REPLACEMENTS = Map.of(
        "net.minecraft.world.level.biome.Biome", Map.of(
            "f_47437_", "getModifiedClimateSettings",
            "f_47443_", "getModifiedSpecialEffects"
        ),
        "net.minecraft.world.level.levelgen.structure.Structure", Map.of(
            "f_226555_", "getModifiedStructureSettings"
        ),
        "net.minecraft.world.effect.MobEffectInstance", Map.of(
            "f_19502_", ASMAPI.mapMethod("m_19544_")
        ),
        "net.minecraft.world.level.block.LiquidBlock", Map.of(
            "f_54689_", "getFluid"
        ),
        "net.minecraft.world.item.BucketItem", Map.of(
            "f_40687_", "getFluid"
        ),
        "net.minecraft.world.level.block.StairBlock", Map.of(
            "f_56858_", "getModelBlock",
            "f_56859_", "getModelState"
        ),
        "net.minecraft.world.level.block.FlowerPotBlock", Map.of(
            "f_53525_", ASMAPI.mapMethod("m_53560_")
        ),
        "net.minecraft.world.item.ItemStack", Map.of(
            "f_41589_", ASMAPI.mapMethod("m_41720_")
        )
    );
    private static final Logger LOGGER = LogUtils.getLogger();
    private final String accessWidenerResource;
    private final Map<String, String> mappedReplacements;
    // Method redirects to ensure 2-way compatibility with Forge's API
    private final EnhancedRemapper remapper;

    public static Transformer.Factory transformer(String accessWidenerResource, IMappingFile mappings, IMappingFile remapperMapping) {
        return ctx -> new FieldToMethodTransformer(accessWidenerResource, mappings, new EnhancedRemapper(ctx.getClassProvider(), remapperMapping, ctx.getLog()));
    }

    public FieldToMethodTransformer(String accessWidenerResource, IMappingFile mappings, EnhancedRemapper remapper) {
        this.accessWidenerResource = accessWidenerResource;
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        REPLACEMENTS.forEach((cls, replacements) -> {
            IMappingFile.IClass classMap = Objects.requireNonNull(mappings.getClass(cls.replace('.', '/')));
            replacements.forEach((field, getter) -> builder.put(classMap.remapField(field), getter));
        });
        this.mappedReplacements = builder.build();
        this.remapper = remapper;
    }

    @Override
    public ClassEntry process(ClassEntry entry) {
        ClassReader reader = new ClassReader(entry.getData());
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        if (processClass(node)) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return ClassEntry.create(entry.getName(), entry.getTime(), writer.toByteArray());
        }
        return entry;
    }

    @Override
    public ResourceEntry process(ResourceEntry entry) {
        if (entry.getName().equals(this.accessWidenerResource)) {
            AccessWidenerWriter writer = new AccessWidenerWriter();
            AccessWidenerVisitor filter = new FilteringAccessWidenerVisitor(this.mappedReplacements.keySet(), writer);
            AccessWidenerReader reader = new AccessWidenerReader(filter);
            reader.read(entry.getData());
            return ResourceEntry.create(entry.getName(), entry.getTime(), writer.write());
        }
        return entry;
    }

    private boolean processClass(ClassNode cls) {
        boolean replaced = false;
        for (MethodNode method : cls.methods) {
            for (ListIterator<AbstractInsnNode> iterator = method.instructions.iterator(); iterator.hasNext(); ) {
                AbstractInsnNode insn = iterator.next();
                if (insn instanceof FieldInsnNode fieldInsn) {
                    for (Map.Entry<String, String> entry : this.mappedReplacements.entrySet()) {
                        String source = entry.getKey();
                        if (source.equals(fieldInsn.name)) {
                            LOGGER.trace("Replacing field getter {} to method {} in {}#{}", source, entry.getValue(), cls.name, method.name);
                            iterator.remove();
                            String getterDesc = "()" + fieldInsn.desc;
                            MethodInsnNode getterCall = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, fieldInsn.owner, entry.getValue(), getterDesc, false);
                            iterator.add(getterCall);
                            replaced = true;
                        }
                    }
                }
                else if (insn instanceof MethodInsnNode methodInsn) {
                    String mappedName = this.remapper.getClass(methodInsn.owner)
                        .flatMap(c -> c.getMethod(methodInsn.name, methodInsn.desc))
                        .map(EnhancedRemapper.MClass.MMethod::getMapped)
                        .orElse(null);
                    if (mappedName != null) {
                        methodInsn.name = mappedName;
                        replaced = true;
                    }
                }
            }
        }
        return replaced;
    }

    private static class FilteringAccessWidenerVisitor extends ForwardingVisitor {
        private final Collection<String> exclude;

        public FilteringAccessWidenerVisitor(Collection<String> exclude, AccessWidenerVisitor... visitors) {
            super(visitors);
            this.exclude = exclude;
        }

        @Override
        public void visitMethod(String owner, String name, String descriptor, AccessWidenerReader.AccessType access, boolean transitive) {
            if (!this.exclude.contains(name)) {
                super.visitMethod(owner, name, descriptor, access, transitive);
            }
        }

        @Override
        public void visitField(String owner, String name, String descriptor, AccessWidenerReader.AccessType access, boolean transitive) {
            if (!this.exclude.contains(name)) {
                super.visitField(owner, name, descriptor, access, transitive);
            }
        }
    }
}
