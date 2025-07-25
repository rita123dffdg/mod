package com.mca.companion;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.Set;
import java.util.List;

@Mod("mca_companion")
public class MCACompanionMod {

    private static final Map<UUID, UUID> handHoldingPairs = new HashMap<>();
    private static final Map<UUID, Long> handHoldStartTime = new HashMap<>();
    private static final Map<UUID, BlockPos> sittingNPCs = new HashMap<>();
    private static final Set<Block> FURNITURE_BLOCKS = Set.of(
        Blocks.OAK_STAIRS, Blocks.BIRCH_STAIRS, Blocks.SPRUCE_STAIRS,
        Blocks.JUNGLE_STAIRS, Blocks.ACACIA_STAIRS, Blocks.DARK_OAK_STAIRS,
        Blocks.CRIMSON_STAIRS, Blocks.WARPED_STAIRS
    );

    @OnlyIn(Dist.CLIENT)
    public static KeyMapping INTERACT_KEY;

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        INTERACT_KEY = new KeyMapping(
            "key.mca_companion.interact",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            "key.categories.mca_companion"
        );
        event.register(INTERACT_KEY);
    }

    @EventBusSubscriber(modid = "mca_companion", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientEvents {
        
        @OnlyIn(Dist.CLIENT)
        @SubscribeEvent
        public static void onKeyPressed(InputEvent.Key event) {
            if (event.getAction() != GLFW.GLFW_PRESS) return;
            if (event.getKey() != GLFW.GLFW_KEY_Z) return;
            
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            
            Player player = mc.player;
            LivingEntity targetNPC = findNearestNPC(player);
            
            if (targetNPC != null) {
                if (player.isCrouching()) {
                    handleFurnitureInteraction(player, targetNPC);
                } else {
                    handleHandHolding(player, targetNPC);
                }
            } else {
                player.sendSystemMessage(Component.literal("No NPC nearby to interact with"));
            }
        }
    }

    @EventBusSubscriber(modid = "mca_companion", bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class CommonEvents {

        @SubscribeEvent
        public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            
            Player player = event.player;
            UUID playerId = player.getUUID();
            
            if (handHoldingPairs.containsKey(playerId)) {
                UUID partnerId = handHoldingPairs.get(playerId);
                Entity partner = findEntityByUUID(player.level(), partnerId);
                
                if (partner instanceof LivingEntity) {
                    maintainHandHolding(player, (LivingEntity) partner);
                } else {
                    handHoldingPairs.remove(playerId);
                    handHoldingPairs.remove(partnerId);
                    handHoldStartTime.remove(playerId);
                }
            }
        }

        @SubscribeEvent
        public static void onWorldTick(TickEvent.LevelTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            
            sittingNPCs.entrySet().removeIf(entry -> {
                UUID npcId = entry.getKey();
                BlockPos furniturePos = entry.getValue();
                
                Entity npc = findEntityByUUID(event.level, npcId);
                if (npc == null) return true;
                
                Block block = event.level.getBlockState(furniturePos).getBlock();
                if (FURNITURE_BLOCKS.contains(block)) {
                    npc.setPos(furniturePos.getX() + 0.5, furniturePos.getY() + 0.5, furniturePos.getZ() + 0.5);
                    return false;
                } else {
                    return true;
                }
            });
        }
    }

    private static void handleHandHolding(Player player, LivingEntity npc) {
        UUID playerId = player.getUUID();
        UUID npcId = npc.getUUID();

        if (handHoldingPairs.containsKey(playerId)) {
            UUID currentPartner = handHoldingPairs.remove(playerId);
            handHoldingPairs.remove(currentPartner);
            handHoldStartTime.remove(playerId);
            
            player.sendSystemMessage(Component.literal("You let go of " + npc.getName().getString() + "'s hand"));
        } else {
            handHoldingPairs.put(playerId, npcId);
            handHoldingPairs.put(npcId, playerId);
            handHoldStartTime.put(playerId, System.currentTimeMillis());
            
            player.sendSystemMessage(Component.literal("You are now holding hands with " + npc.getName().getString()));
        }
    }

    private static void handleFurnitureInteraction(Player player, LivingEntity npc) {
        Level world = player.level();
        BlockPos npcPos = npc.blockPosition();
        
        BlockPos furniturePos = findNearbyFurniture(world, npcPos, 3);
        
        if (furniturePos != null) {
            UUID npcId = npc.getUUID();
            
            if (sittingNPCs.containsKey(npcId)) {
                sittingNPCs.remove(npcId);
                npc.setPos(furniturePos.getX() + 0.5, furniturePos.getY() + 1, furniturePos.getZ() + 0.5);
                player.sendSystemMessage(Component.literal(npc.getName().getString() + " stands up"));
            } else {
                sittingNPCs.put(npcId, furniturePos);
                npc.setPos(furniturePos.getX() + 0.5, furniturePos.getY() + 0.5, furniturePos.getZ() + 0.5);
                player.sendSystemMessage(Component.literal(npc.getName().getString() + " sits down"));
            }
        } else {
            player.sendSystemMessage(Component.literal("No furniture nearby for " + npc.getName().getString() + " to sit on"));
        }
    }

    private static void maintainHandHolding(Player player, LivingEntity partner) {
        double distance = player.distanceTo(partner);
        
        if (distance > 3.0) {
            UUID playerId = player.getUUID();
            UUID partnerId = partner.getUUID();
            
            handHoldingPairs.remove(playerId);
            handHoldingPairs.remove(partnerId);
            handHoldStartTime.remove(playerId);
            
            player.sendSystemMessage(Component.literal("You lost hold of " + partner.getName().getString() + "'s hand"));
            return;
        }
        
        if (partner instanceof Mob && distance > 1.5) {
            Vec3 playerPos = player.position();
            Vec3 direction = playerPos.subtract(partner.position()).normalize();
            Vec3 targetPos = playerPos.subtract(direction.scale(1.2));
            
            partner.getNavigation().moveTo(targetPos.x, targetPos.y, targetPos.z, 1.0);
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static LivingEntity findNearestNPC(Player player) {
        Level world = player.level();
        AABB searchArea = new AABB(player.blockPosition()).inflate(3.0);
        
        List<LivingEntity> entities = world.getEntitiesOfClass(LivingEntity.class, searchArea);
        
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (LivingEntity entity : entities) {
            if (entity != player && isVillagerOrNPC(entity)) {
                double distance = player.distanceTo(entity);
                if (distance < nearestDistance) {
                    nearest = entity;
                    nearestDistance = distance;
                }
            }
        }
        
        return nearest;
    }

    private static boolean isVillagerOrNPC(Entity entity) {
        String entityType = entity.getType().getDescriptionId();
        return entityType.contains("villager") || entityType.contains("mca");
    }

    private static BlockPos findNearbyFurniture(Level world, BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    Block block = world.getBlockState(pos).getBlock();
                    if (FURNITURE_BLOCKS.contains(block)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static Entity findEntityByUUID(Level world, UUID uuid) {
        for (Entity entity : world.getAllEntities()) {
            if (entity.getUUID().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }
}
