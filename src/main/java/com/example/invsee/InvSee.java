package com.example.invsee;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;

public final class InvSee extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private final Map<Inventory, Player> onlineInvTargets = new HashMap<>();
    private final Map<Inventory, Player> onlineEnderTargets = new HashMap<>();
    private final Map<Inventory, UUID> offlineInvTargets = new HashMap<>();
    private final Map<Inventory, UUID> offlineEnderTargets = new HashMap<>();

    // Cached reflection handles
    private Method playerGetData;
    private Method asBukkitCopy;
    private Method asNMSCopy;
    private Method nbtIoWrite;
    private Method codecParse;
    private Method codecEncode;
    private Object registryOps;      // RegistryOps with registry access for codec
    private Object itemCodec;
    private Class<?> compoundTagClass;
    private Class<?> listTagClass;
    private Class<?> tagClass;

    @Override
    public void onEnable() {
        registerCommand("invsee", List.of("invs"));
        registerCommand("endersee", List.of("ecsee"));
        getServer().getPluginManager().registerEvents(this, this);
        initReflection();
        getLogger().info("InvSee enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("InvSee disabled!");
    }

    private void registerCommand(String name, List<String> aliases) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
            if (!aliases.isEmpty()) cmd.setAliases(aliases);
        }
    }

    // ==================== Reflection Init ====================

    private void initReflection() {
        try {
            Object craftServer = Bukkit.getServer();

            // RegistryAccess + RegistryOps for codec (needed to resolve item IDs)
            Object dedicatedServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            Object registryAccess = dedicatedServer.getClass().getMethod("registryAccess").invoke(dedicatedServer);
            Object nbtOps = Class.forName("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
            Class<?> registryOpsClass = Class.forName("net.minecraft.resources.RegistryOps");
            Method registryOpsCreate = null;
            for (Method m : registryOpsClass.getMethods()) {
                if (m.getName().equals("create") && m.getParameterCount() == 2) {
                    registryOpsCreate = m;
                    break;
                }
            }
            if (registryOpsCreate != null) {
                registryOps = registryOpsCreate.invoke(null, nbtOps, registryAccess);
            } else {
                // Fallback: use plain NbtOps if RegistryOps.create not found
                registryOps = nbtOps;
            }

            // Derive NMS ItemStack via CraftItemStack bridge (remapping-safe)
            Class<?> craftItemStack = craftServer.getClass().getClassLoader()
                    .loadClass("org.bukkit.craftbukkit.inventory.CraftItemStack");
            ItemStack dummy = new ItemStack(Material.AIR);
            asNMSCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            Object nmsDummy = asNMSCopy.invoke(null, dummy);
            Class<?> nmsItemStack = nmsDummy.getClass();
            asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);

            // ItemStack.CODEC
            itemCodec = nmsItemStack.getField("CODEC").get(null);

            // Codec methods
            for (Method m : itemCodec.getClass().getMethods()) {
                if (m.getName().equals("parse") && m.getParameterCount() == 2) codecParse = m;
                if (m.getName().equals("encodeStart") && m.getParameterCount() == 2) codecEncode = m;
            }

            // CraftOfflinePlayer.getData() -> CompoundTag
            Class<?> craftOfflinePlayer = craftServer.getClass().getClassLoader()
                    .loadClass("org.bukkit.craftbukkit.CraftOfflinePlayer");
            playerGetData = craftOfflinePlayer.getDeclaredMethod("getData");
            playerGetData.setAccessible(true);

            // NBT classes
            compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
            listTagClass = Class.forName("net.minecraft.nbt.ListTag");
            tagClass = Class.forName("net.minecraft.nbt.Tag");

            // NbtIo.writeCompressed(CompoundTag, Path)
            nbtIoWrite = Class.forName("net.minecraft.nbt.NbtIo")
                    .getMethod("writeCompressed", compoundTagClass, Path.class);

        } catch (Exception e) {
            getLogger().severe("Failed to init reflection: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== NBT Helpers ====================

    /** 1.21.5+: CompoundTag.getList() returns Optional<ListTag>, must unwrap */
    private Object unwrapOptional(Object obj) throws Exception {
        if (obj == null) return null;
        // If it has an "orElseThrow" method, it's an Optional
        try {
            return obj.getClass().getMethod("orElseThrow").invoke(obj);
        } catch (NoSuchMethodException e) {
            return obj; // Not an Optional, return as-is
        }
    }

    private Object loadPlayerData(OfflinePlayer offline) throws Exception {
        return unwrapOptional(playerGetData.invoke(offline));
    }

    private void savePlayerData(UUID uuid, Object rootTag) throws Exception {
        File worldDir = Bukkit.getWorlds().get(0).getWorldFolder();
        File dataFile = new File(worldDir, "playerdata/" + uuid + ".dat");
        if (dataFile.exists()) {
            nbtIoWrite.invoke(null, rootTag, dataFile.toPath());
        }
    }

    /** Parse NBT tag to NMS ItemStack via ItemStack.CODEC.parse(RegistryOps, tag) */
    private Object parseNmsItem(Object tag) throws Exception {
        Object dataResult = codecParse.invoke(itemCodec, registryOps, tag);
        Object optional = dataResult.getClass().getMethod("result").invoke(dataResult);
        return optional.getClass().getMethod("orElseThrow").invoke(optional);
    }

    /** Encode NMS ItemStack to NBT tag via ItemStack.CODEC.encodeStart(RegistryOps, stack) */
    private Object saveNmsItem(Object nmsStack) throws Exception {
        Object dataResult = codecEncode.invoke(itemCodec, registryOps, nmsStack);
        Object optional = dataResult.getClass().getMethod("result").invoke(dataResult);
        return optional.getClass().getMethod("orElseThrow").invoke(optional);
    }

    // ==================== NBT Slot Mapping ====================

    private static int nbtSlotToMirror(byte slot) {
        if (slot >= 0 && slot <= 35) return slot;
        if (slot == 100) return 36; // boots
        if (slot == 101) return 37; // leggings
        if (slot == 102) return 38; // chestplate
        if (slot == 103) return 39; // helmet
        if (slot == -106) return 40; // offhand
        return -1;
    }

    private static int mirrorSlotToNbt(int mirrorSlot) {
        if (mirrorSlot >= 0 && mirrorSlot <= 35) return mirrorSlot;
        if (mirrorSlot == 36) return 100;
        if (mirrorSlot == 37) return 101;
        if (mirrorSlot == 38) return 102;
        if (mirrorSlot == 39) return 103;
        if (mirrorSlot == 40) return -106;
        return Integer.MIN_VALUE;
    }

    private static boolean isManagedSlot(byte slot) {
        return (slot >= 0 && slot <= 35) || (slot >= 100 && slot <= 103) || slot == -106;
    }

    // ==================== Command Handler ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by a player.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /" + command.getName() + " <player>");
            return true;
        }

        String cmd = command.getName().toLowerCase();
        String perm = cmd.equals("invsee") ? "invsee.view" : "invsee.endersee";
        if (!sender.hasPermission(perm)) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target != null) {
            if (target.equals(player)) {
                sender.sendMessage("§cYou cannot view your own " + (cmd.equals("invsee") ? "inventory" : "ender chest") + ".");
                return true;
            }
            player.getScheduler().run(this, task -> {
                if (cmd.equals("invsee")) openOnlineInvsee(player, target);
                else openOnlineEnderChest(player, target);
            }, null);
        } else {
            player.getScheduler().run(this, task -> {
                if (cmd.equals("invsee")) openOfflineInvsee(player, args[0]);
                else openOfflineEnderChest(player, args[0]);
            }, null);
        }
        return true;
    }

    // ==================== Online Invsee ====================

    private void openOnlineInvsee(Player viewer, Player target) {
        Component title = Component.text(target.getName() + " 的背包")
                .color(NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false);
        Inventory mirror = Bukkit.createInventory(null, 45, title);

        ItemStack[] contents = target.getInventory().getContents();
        for (int i = 0; i < 36; i++) mirror.setItem(i, contents[i]);

        ItemStack[] armor = target.getInventory().getArmorContents();
        for (int i = 0; i < 4; i++) mirror.setItem(36 + i, armor[i]);

        mirror.setItem(40, target.getInventory().getItemInOffHand());

        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        glass.editMeta(meta -> meta.displayName(Component.text("§7↑ 装备栏 ↑").decoration(TextDecoration.ITALIC, false)));
        for (int i = 41; i <= 44; i++) mirror.setItem(i, glass);

        onlineInvTargets.put(mirror, target);
        viewer.openInventory(mirror);
    }

    private void openOnlineEnderChest(Player viewer, Player target) {
        Component title = Component.text(target.getName() + " 的末影箱")
                .color(NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false);
        Inventory mirror = Bukkit.createInventory(null, 27, title);
        mirror.setContents(target.getEnderChest().getContents());
        onlineEnderTargets.put(mirror, target);
        viewer.openInventory(mirror);
    }

    // ==================== Offline Invsee ====================

    @SuppressWarnings("deprecation")
    private void openOfflineInvsee(Player viewer, String targetName) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        if (!offline.hasPlayedBefore()) {
            viewer.sendMessage("§cPlayer has never joined: " + targetName);
            return;
        }

        try {
            Object rootTag = loadPlayerData(offline);
            Object invList = unwrapOptional(
                    rootTag.getClass().getMethod("getList", String.class).invoke(rootTag, "Inventory"));

            Component title = Component.text(targetName + " 的背包")
                    .color(NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.ITALIC, false);
            Inventory mirror = Bukkit.createInventory(null, 45, title);

            if (invList != null) {
                int listSize = (int) invList.getClass().getMethod("size").invoke(invList);
                for (int i = 0; i < listSize; i++) {
                    Object itemTag = unwrapOptional(
                            listTagClass.getMethod("getCompound", int.class).invoke(invList, i));
                    byte slot = (byte) compoundTagClass.getMethod("getByte", String.class).invoke(itemTag, "Slot");
                    Object nmsStack = parseNmsItem(itemTag);
                    boolean empty = (boolean) nmsStack.getClass().getMethod("isEmpty").invoke(nmsStack);
                    if (!empty) {
                        int mirrorSlot = nbtSlotToMirror(slot);
                        if (mirrorSlot >= 0 && mirrorSlot < 41) {
                            mirror.setItem(mirrorSlot, (ItemStack) asBukkitCopy.invoke(null, nmsStack));
                        }
                    }
                }
            }

            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            glass.editMeta(meta -> meta.displayName(Component.text("§7↑ 装备栏 ↑").decoration(TextDecoration.ITALIC, false)));
            for (int i = 41; i <= 44; i++) mirror.setItem(i, glass);

            offlineInvTargets.put(mirror, offline.getUniqueId());
            viewer.openInventory(mirror);

        } catch (Exception e) {
            getLogger().warning("Failed loading offline inventory for " + targetName + ": " + e.getMessage());
            viewer.sendMessage("§cFailed to load player data: " + e.getMessage());
        }
    }

    private void saveOfflineInvsee(Inventory mirror, UUID uuid) {
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            Object rootTag = loadPlayerData(offline);
            Object oldInvList = unwrapOptional(
                    rootTag.getClass().getMethod("getList", String.class).invoke(rootTag, "Inventory"));

            Object newList = listTagClass.getConstructor().newInstance();
            Method listAdd = listTagClass.getMethod("add", tagClass);

            if (oldInvList != null) {
                int oldSize = (int) oldInvList.getClass().getMethod("size").invoke(oldInvList);
                for (int i = 0; i < oldSize; i++) {
                    Object itemTag = unwrapOptional(
                            listTagClass.getMethod("getCompound", int.class).invoke(oldInvList, i));
                    byte slot = (byte) compoundTagClass.getMethod("getByte", String.class).invoke(itemTag, "Slot");
                    if (isManagedSlot(slot)) continue;
                    listAdd.invoke(newList, itemTag);
                }
            }

            for (int mirrorSlot = 0; mirrorSlot < 41; mirrorSlot++) {
                ItemStack item = mirror.getItem(mirrorSlot);
                int nbtSlot = mirrorSlotToNbt(mirrorSlot);
                if (nbtSlot == Integer.MIN_VALUE) continue;
                if (item == null || item.getType().isEmpty()) continue;
                Object nmsStack = asNMSCopy.invoke(null, item);
                Object tag = saveNmsItem(nmsStack);
                compoundTagClass.getMethod("putByte", String.class, byte.class).invoke(tag, "Slot", (byte) nbtSlot);
                listAdd.invoke(newList, tag);
            }

            Method put = compoundTagClass.getMethod("put", String.class, tagClass);
            put.invoke(rootTag, "Inventory", newList);
            savePlayerData(uuid, rootTag);

        } catch (Exception e) {
            getLogger().warning("Failed saving offline inventory for " + uuid + ": " + e.getMessage());
        }
    }

    // ==================== Offline EnderChest ====================

    @SuppressWarnings("deprecation")
    private void openOfflineEnderChest(Player viewer, String targetName) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        if (!offline.hasPlayedBefore()) {
            viewer.sendMessage("§cPlayer has never joined: " + targetName);
            return;
        }

        try {
            Object rootTag = loadPlayerData(offline);
            Object enderList = unwrapOptional(
                    rootTag.getClass().getMethod("getList", String.class).invoke(rootTag, "EnderItems"));
            int size = (int) enderList.getClass().getMethod("size").invoke(enderList);

            Component title = Component.text(targetName + " 的末影箱")
                    .color(NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.ITALIC, false);
            Inventory inv = Bukkit.createInventory(null, 27, title);

            for (int i = 0; i < size && i < 27; i++) {
                Object itemTag = unwrapOptional(
                        listTagClass.getMethod("getCompound", int.class).invoke(enderList, i));
                Object nmsStack = parseNmsItem(itemTag);
                boolean empty = (boolean) nmsStack.getClass().getMethod("isEmpty").invoke(nmsStack);
                if (!empty) {
                    inv.setItem(i, (ItemStack) asBukkitCopy.invoke(null, nmsStack));
                }
            }

            offlineEnderTargets.put(inv, offline.getUniqueId());
            viewer.openInventory(inv);

        } catch (Exception e) {
            getLogger().warning("Failed loading offline ender chest for " + targetName + ": " + e.getMessage());
            viewer.sendMessage("§cFailed to load player data: " + e.getMessage());
        }
    }

    private void saveOfflineEnderChest(Inventory inv, UUID uuid) {
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            Object rootTag = loadPlayerData(offline);

            Object newList = listTagClass.getConstructor().newInstance();
            Method listAdd = listTagClass.getMethod("add", tagClass);

            for (int i = 0; i < 27; i++) {
                ItemStack item = inv.getItem(i);
                Object tag;
                if (item == null || item.getType().isEmpty()) {
                    tag = compoundTagClass.getConstructor().newInstance();
                } else {
                    tag = saveNmsItem(asNMSCopy.invoke(null, item));
                }
                listAdd.invoke(newList, tag);
            }

            Method put = compoundTagClass.getMethod("put", String.class, tagClass);
            put.invoke(rootTag, "EnderItems", newList);
            savePlayerData(uuid, rootTag);

        } catch (Exception e) {
            getLogger().warning("Failed saving offline ender chest for " + uuid + ": " + e.getMessage());
        }
    }

    // ==================== Inventory Events ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean isOurs = onlineInvTargets.containsKey(top) || onlineEnderTargets.containsKey(top)
                || offlineInvTargets.containsKey(top) || offlineEnderTargets.containsKey(top);
        if (!isOurs) return;

        if (!event.getClickedInventory().equals(top)) return;

        if (!event.getWhoClicked().hasPermission("invsee.modify")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean isOurs = onlineInvTargets.containsKey(top) || onlineEnderTargets.containsKey(top)
                || offlineInvTargets.containsKey(top) || offlineEnderTargets.containsKey(top);
        if (!isOurs) return;

        if (!event.getWhoClicked().hasPermission("invsee.modify")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();

        Player invTarget = onlineInvTargets.remove(inv);
        if (invTarget != null) {
            ItemStack[] contents = new ItemStack[36];
            for (int i = 0; i < 36; i++) contents[i] = inv.getItem(i);
            invTarget.getInventory().setContents(contents);
            ItemStack[] armor = new ItemStack[4];
            for (int i = 0; i < 4; i++) armor[i] = inv.getItem(36 + i);
            invTarget.getInventory().setArmorContents(armor);
            invTarget.getInventory().setItemInOffHand(inv.getItem(40));
            return;
        }

        Player enderTarget = onlineEnderTargets.remove(inv);
        if (enderTarget != null) {
            enderTarget.getEnderChest().setContents(inv.getContents());
            return;
        }

        UUID invUuid = offlineInvTargets.remove(inv);
        if (invUuid != null) {
            saveOfflineInvsee(inv, invUuid);
            return;
        }

        UUID enderUuid = offlineEnderTargets.remove(inv);
        if (enderUuid != null) {
            saveOfflineEnderChest(inv, enderUuid);
        }
    }

    // ==================== Tab Complete ====================

    @SuppressWarnings("deprecation")
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) return Collections.emptyList();

        String cmd = command.getName().toLowerCase();
        String perm = cmd.equals("invsee") ? "invsee.view" : "invsee.endersee";
        if (!sender.hasPermission(perm)) return Collections.emptyList();

        Set<String> names = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null) names.add(op.getName());
        }

        List<String> result = new ArrayList<>();
        String lower = args[0].toLowerCase();
        for (String name : names) {
            if (name.toLowerCase().startsWith(lower)) result.add(name);
        }
        return result;
    }
}
