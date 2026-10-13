package com.exojosh.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Safe reflection-based compatibility bridge for Minecraft mods in DeceasedCraft:
 * 1. Timeless and Classics Guns (TACZ): Gun ammo, max capacity, fire mode, reserve ammo
 * 2. Legendary Survival Overhaul (LSO): Hydration/thirst and temperature
 * 3. ParCool: Parkour stamina
 * 4. AppleSkin: Saturation and exhaustion
 * 5. Inventory HUD+ / ArmorPoints++: Equipped armor durability, toughness, active potion effects
 * 6. Target Inspector (The One Probe / WAILA style): Crosshair target block or mob
 */
public class ModHudCompat {

    // --- Records serialized into JSON ---
    public record GunHud(
            boolean active,
            String gunId,
            String gunName,
            int ammo,
            int maxAmmo,
            String fireMode,
            int reserveAmmo
    ) {}

    public record SurvivalHud(
            boolean hasThirst,
            float thirst,
            float thirstSaturation,
            boolean hasTemp,
            float temperature,
            String tempStatus,
            boolean hasStamina,
            int stamina,
            int maxStamina
    ) {}

    public record EffectHud(
            String id,
            String name,
            int durationTicks,
            int amplifier,
            boolean beneficial
    ) {}

    public record TargetHud(
            String type,
            String name,
            String mod,
            float health,
            float maxHealth
    ) {}

    public record ArmorHud(
            String slot,
            String itemId,
            int count,
            int damage,
            int maxDamage
    ) {}

    // --- TACZ Reflection Cache ---
    private static boolean taczChecked = false;
    private static Method iGunGetOrNull = null;
    private static Method iGunGetCurrentAmmo = null;
    private static Method iGunGetFireMode = null;
    private static Method iGunGetGunId = null;
    private static Method timelessGetCommonGunIndex = null;
    private static Method commonGunIndexGetGunData = null;
    private static Method gunDataGetAmmoAmount = null;
    private static Method gunDataGetAmmoId = null;

    // --- LSO Reflection Cache ---
    private static boolean lsoChecked = false;
    private static Method lsoGetThirstCap = null;
    private static Method lsoGetTempCap = null;
    private static Method thirstGetHydration = null;
    private static Method thirstGetSaturation = null;
    private static Method tempGetLevel = null;
    private static Method tempGetEnum = null;

    // --- ParCool Reflection Cache ---
    private static boolean parcoolChecked = false;
    private static Method parcoolStaminaGet = null;
    private static Method parcoolStaminaGetValue = null;
    private static Method parcoolStaminaGetMaxValue = null;

    // -------------------------------------------------------------------------
    // 1. TACZ Gun HUD
    // -------------------------------------------------------------------------
    public static GunHud getGunHud(Player player) {
        if (player == null) return new GunHud(false, "", "", 0, 0, "", 0);
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.isEmpty()) return new GunHud(false, "", "", 0, 0, "", 0);

        initTaczReflection();
        if (iGunGetOrNull == null) return new GunHud(false, "", "", 0, 0, "", 0);

        try {
            Object iGun = iGunGetOrNull.invoke(null, mainHand);
            if (iGun == null) return new GunHud(false, "", "", 0, 0, "", 0);

            int ammo = (int) iGunGetCurrentAmmo.invoke(iGun, mainHand);
            Object fireModeObj = iGunGetFireMode.invoke(iGun, mainHand);
            String fireMode = fireModeObj != null ? fireModeObj.toString() : "SEMI";
            ResourceLocation gunIdLoc = (ResourceLocation) iGunGetGunId.invoke(iGun, mainHand);
            String gunId = gunIdLoc != null ? gunIdLoc.toString() : "";
            String gunName = mainHand.getHoverName().getString();

            int maxAmmo = ammo;
            ResourceLocation ammoId = null;
            if (timelessGetCommonGunIndex != null && gunIdLoc != null) {
                @SuppressWarnings("unchecked")
                Optional<Object> commonIndexOpt = (Optional<Object>) timelessGetCommonGunIndex.invoke(null, gunIdLoc);
                if (commonIndexOpt != null && commonIndexOpt.isPresent()) {
                    Object commonIndex = commonIndexOpt.get();
                    Object gunData = commonGunIndexGetGunData.invoke(commonIndex);
                    if (gunData != null) {
                        maxAmmo = (int) gunDataGetAmmoAmount.invoke(gunData);
                        ammoId = (ResourceLocation) gunDataGetAmmoId.invoke(gunData);
                    }
                }
            }

            int reserveAmmo = 0;
            if (ammoId != null) {
                String ammoKey = ammoId.toString();
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack invStack = player.getInventory().getItem(i);
                    if (!invStack.isEmpty()) {
                        ResourceLocation itemLoc = BuiltInRegistries.ITEM.getKey(invStack.getItem());
                        if (itemLoc != null && itemLoc.toString().equals(ammoKey)) {
                            reserveAmmo += invStack.getCount();
                        }
                    }
                }
            }

            return new GunHud(true, gunId, gunName, ammo, maxAmmo, fireMode, reserveAmmo);
        } catch (Exception e) {
            return new GunHud(false, "", "", 0, 0, "", 0);
        }
    }

    private static void initTaczReflection() {
        if (taczChecked) return;
        taczChecked = true;
        try {
            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            iGunGetOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
            iGunGetCurrentAmmo = iGunClass.getMethod("getCurrentAmmoCount", ItemStack.class);
            iGunGetFireMode = iGunClass.getMethod("getFireMode", ItemStack.class);
            iGunGetGunId = iGunClass.getMethod("getGunId", ItemStack.class);

            Class<?> timelessClass = Class.forName("com.tacz.guns.api.TimelessAPI");
            timelessGetCommonGunIndex = timelessClass.getMethod("getCommonGunIndex", ResourceLocation.class);

            Class<?> commonIndexClass = Class.forName("com.tacz.guns.resource.index.CommonGunIndex");
            commonGunIndexGetGunData = commonIndexClass.getMethod("getGunData");

            Class<?> gunDataClass = Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData");
            gunDataGetAmmoAmount = gunDataClass.getMethod("getAmmoAmount");
            gunDataGetAmmoId = gunDataClass.getMethod("getAmmoId");
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // 2. LSO Survival & ParCool Stamina HUD
    // -------------------------------------------------------------------------
    public static SurvivalHud getSurvivalHud(Player player) {
        if (player == null) return new SurvivalHud(false, 0, 0, false, 0, "NORMAL", false, 0, 0);

        boolean hasThirst = false;
        float thirst = 20.0f;
        float thirstSat = 0.0f;

        boolean hasTemp = false;
        float tempVal = 0.0f;
        String tempStatus = "NORMAL";

        boolean hasStamina = false;
        int stamina = 0;
        int maxStamina = 0;

        // LSO Reflection
        initLsoReflection();
        if (lsoGetThirstCap != null) {
            try {
                Object thirstCap = lsoGetThirstCap.invoke(null, player);
                if (thirstCap != null) {
                    hasThirst = true;
                    Object hVal = thirstGetHydration.invoke(thirstCap);
                    if (hVal instanceof Number n) thirst = n.floatValue();
                    Object sVal = thirstGetSaturation.invoke(thirstCap);
                    if (sVal instanceof Number n) thirstSat = n.floatValue();
                }
            } catch (Exception ignored) {}
        }
        if (lsoGetTempCap != null) {
            try {
                Object tempCap = lsoGetTempCap.invoke(null, player);
                if (tempCap != null) {
                    hasTemp = true;
                    Object tVal = tempGetLevel.invoke(tempCap);
                    if (tVal instanceof Number n) tempVal = n.floatValue();
                    Object eVal = tempGetEnum.invoke(tempCap);
                    if (eVal != null) tempStatus = eVal.toString();
                }
            } catch (Exception ignored) {}
        }

        // ParCool Reflection
        initParcoolReflection();
        if (parcoolStaminaGet != null) {
            try {
                Object stObj = parcoolStaminaGet.invoke(null, player);
                if (stObj != null) {
                    hasStamina = true;
                    stamina = (int) parcoolStaminaGetValue.invoke(stObj);
                    maxStamina = (int) parcoolStaminaGetMaxValue.invoke(stObj);
                }
            } catch (Exception ignored) {}
        }

        return new SurvivalHud(hasThirst, thirst, thirstSat, hasTemp, tempVal, tempStatus, hasStamina, stamina, maxStamina);
    }

    private static void initLsoReflection() {
        if (lsoChecked) return;
        lsoChecked = true;
        try {
            Class<?> capUtilClass = Class.forName("sfiomn.legendarysurvivaloverhaul.util.CapabilityUtil");
            lsoGetThirstCap = capUtilClass.getMethod("getThirstCapability", Player.class);
            lsoGetTempCap = capUtilClass.getMethod("getTempCapability", Player.class);

            Class<?> thirstCapClass = Class.forName("sfiomn.legendarysurvivaloverhaul.common.capabilities.thirst.ThirstCapability");
            thirstGetHydration = thirstCapClass.getMethod("getHydrationLevel");
            thirstGetSaturation = thirstCapClass.getMethod("getSaturationLevel");

            Class<?> tempCapClass = Class.forName("sfiomn.legendarysurvivaloverhaul.common.capabilities.temperature.TemperatureCapability");
            tempGetLevel = tempCapClass.getMethod("getTemperatureLevel");
            tempGetEnum = tempCapClass.getMethod("getTemperatureEnum");
        } catch (Throwable ignored) {}
    }

    private static void initParcoolReflection() {
        if (parcoolChecked) return;
        parcoolChecked = true;
        try {
            Class<?> staminaClass = Class.forName("com.alrex.parcool.api.Stamina");
            parcoolStaminaGet = staminaClass.getMethod("get", Player.class);
            parcoolStaminaGetValue = staminaClass.getMethod("getValue");
            parcoolStaminaGetMaxValue = staminaClass.getMethod("getMaxValue");
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // 3. Active Potion Effects (Inventory HUD+ style)
    // -------------------------------------------------------------------------
    public static List<EffectHud> getActiveEffects(Player player) {
        List<EffectHud> list = new ArrayList<>();
        if (player == null) return list;

        for (MobEffectInstance inst : player.getActiveEffects()) {
            if (inst == null || inst.getEffect() == null) continue;
            String id = BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect()).toString();
            String name = inst.getEffect().getDisplayName().getString();
            int duration = inst.getDuration();
            int amplifier = inst.getAmplifier();
            boolean beneficial = inst.getEffect().isBeneficial();
            list.add(new EffectHud(id, name, duration, amplifier, beneficial));
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 4. Equipped Armor Durability (Inventory HUD+ / ArmorPoints++ style)
    // -------------------------------------------------------------------------
    public static List<ArmorHud> getArmorSlots(Player player) {
        List<ArmorHud> list = new ArrayList<>(4);
        if (player == null) return list;

        String[] slotNames = {"FEET", "LEGS", "CHEST", "HEAD"};
        for (int i = 0; i < 4; i++) {
            ItemStack stack = player.getInventory().armor.get(i);
            if (stack.isEmpty()) {
                list.add(new ArmorHud(slotNames[i], "minecraft:air", 0, 0, 0));
            } else {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                boolean dmg = stack.isDamageableItem();
                int damage = dmg ? stack.getDamageValue() : 0;
                int max = dmg ? stack.getMaxDamage() : 0;
                list.add(new ArmorHud(slotNames[i], id, stack.getCount(), damage, max));
            }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 5. Crosshair Target Inspector (The One Probe / Jade style)
    // -------------------------------------------------------------------------
    public static TargetHud getTargetHud(Minecraft client) {
        if (client == null || client.hitResult == null) {
            return new TargetHud("NONE", "", "", 0, 0);
        }

        HitResult hit = client.hitResult;
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bHit) {
            if (client.level != null) {
                BlockPos pos = bHit.getBlockPos();
                BlockState state = client.level.getBlockState(pos);
                String blockName = state.getBlock().getName().getString();
                ResourceLocation reg = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                String mod = reg != null ? reg.getNamespace() : "minecraft";
                return new TargetHud("BLOCK", blockName, mod, 0, 0);
            }
        } else if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult eHit) {
            Entity ent = eHit.getEntity();
            if (ent != null) {
                String entName = ent.getName().getString();
                ResourceLocation reg = BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType());
                String mod = reg != null ? reg.getNamespace() : "minecraft";
                float hp = 0;
                float maxHp = 0;
                if (ent instanceof LivingEntity living) {
                    hp = living.getHealth();
                    maxHp = living.getMaxHealth();
                }
                return new TargetHud("ENTITY", entName, mod, hp, maxHp);
            }
        }

        return new TargetHud("NONE", "", "", 0, 0);
    }
}
