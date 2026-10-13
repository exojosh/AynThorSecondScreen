package com.exojosh.forge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Enumerates all active key bindings for Forge 1.20.1.
 */
public final class KeyBindingCatalogForge {

    private KeyBindingCatalogForge() {}

    public record Entry(
            String id,
            String label,
            String category,
            String boundKey,
            boolean unbound
    ) {}

    public static List<Entry> snapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null || client.options.keyMappings == null) return List.of();

        List<Entry> entries = new ArrayList<>();
        for (KeyMapping binding : client.options.keyMappings) {
            if (binding == null) continue;
            entries.add(new Entry(
                    binding.getName(),
                    Component.translatable(binding.getName()).getString(),
                    Component.translatable(binding.getCategory()).getString(),
                    binding.getTranslatedKeyMessage().getString(),
                    binding.isUnbound()
            ));
        }

        entries.sort(Comparator.comparing(Entry::category).thenComparing(Entry::label));
        return entries;
    }
}
