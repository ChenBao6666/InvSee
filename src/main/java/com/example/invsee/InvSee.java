package com.example.invsee;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

public final class InvSee extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    // For offline save tracking
    private final Map<Inventory, UUID> offlineInvTargets = new HashMap<>();
    private final Map<Inventory, UUID> offlineEnderTargets = new HashMap<>();

    // Track open inventories for event identification
    private final Set<Inventory> openInvseeInventories = new HashSet<>();
    // Track viewer → offline player mapping (for join conflict handling)
    private final Map<Inventory, Player> offlineInvViewers = new HashMap<>();
    private final Map<Inventory, Player> offlineEnderViewers = new HashMap<>();

    // Bound enchanting table locations (player UUID → location string "world,x,y,z")
    private final java.util.Map<java.util.UUID, String> boundEnchantTables = new java.util.HashMap<>();
    private java.io.File bindingsFile;
    private org.bukkit.configuration.file.FileConfiguration bindingsConfig;

    // Cached reflection handles
    private Method playerGetData;
    private Method asBukkitCopy;
    private Method asNMSCopy;
    private Method nbtIoWrite;
    private Method codecParse;
    private Method codecEncode;
    private Object registryOps;
    private Object itemCodec;
    private Class<?> compoundTagClass;
    private Class<?> listTagClass;
    private Class<?> tagClass;
    private Object nmsItemStackEmpty;

    // CraftInventory constructor
    private java.lang.reflect.Constructor<?> craftInventoryCtor;

    @Override
    public void onEnable() {
        registerCommand("invsee", List.of("invs"));
        registerCommand("endersee", List.of("ecsee"));
        registerCommand("enderchest", List.of("ec"));
        registerCommand("workbench", List.of("wb", "craft"));
        registerCommand("anvil", List.of());
        registerCommand("grindstone", List.of());
        registerCommand("enchanttable", List.of("et"));
        registerCommand("stonecutter", List.of("sc"));
        registerCommand("cartography", List.of("ct"));
        registerCommand("loom", List.of());
        getServer().getPluginManager().registerEvents(this, this);
        initReflection();
        loadEnchantBindings();
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

            // RegistryAccess + RegistryOps for codec
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
                registryOps = nbtOps;
            }

            // Derive NMS ItemStack via CraftItemStack bridge
            Class<?> craftItemStackClass = craftServer.getClass().getClassLoader()
                    .loadClass("org.bukkit.craftbukkit.inventory.CraftItemStack");
            ItemStack dummy = new ItemStack(Material.AIR);
            asNMSCopy = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
            Object nmsDummy = asNMSCopy.invoke(null, dummy);
            Class<?> nmsItemStackClass = nmsDummy.getClass();
            asBukkitCopy = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);
            nmsItemStackEmpty = nmsItemStackClass.getField("EMPTY").get(null);

            // ItemStack.CODEC
            itemCodec = nmsItemStackClass.getField("CODEC").get(null);

            for (Method m : itemCodec.getClass().getMethods()) {
                if (m.getName().equals("parse") && m.getParameterCount() == 2) codecParse = m;
                if (m.getName().equals("encodeStart") && m.getParameterCount() == 2) codecEncode = m;
            }

            // CraftOfflinePlayer.getData()
            Class<?> craftOfflinePlayer = craftServer.getClass().getClassLoader()
                    .loadClass("org.bukkit.craftbukkit.CraftOfflinePlayer");
            playerGetData = craftOfflinePlayer.getDeclaredMethod("getData");
            playerGetData.setAccessible(true);

            // NBT classes
            compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
            listTagClass = Class.forName("net.minecraft.nbt.ListTag");
            tagClass = Class.forName("net.minecraft.nbt.Tag");
            getLogger().info("NBT classes: CompoundTag=" + compoundTagClass.getName()
                    + " ListTag=" + listTagClass.getName()
                    + " Tag=" + tagClass.getName());

            // NbtIo.writeCompressed(CompoundTag, Path)
            nbtIoWrite = Class.forName("net.minecraft.nbt.NbtIo")
                    .getMethod("writeCompressed", compoundTagClass, Path.class);

            // CraftInventory constructor: find one that takes a single non-CraftInventory parameter
            Class<?> craftInvClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftInventory");
            for (java.lang.reflect.Constructor<?> ctor : craftInvClass.getConstructors()) {
                Class<?>[] pTypes = ctor.getParameterTypes();
                if (pTypes.length == 1 && !pTypes[0].equals(craftInvClass)) {
                    craftInventoryCtor = ctor;
                    getLogger().info("Found CraftInventory constructor with param: " + pTypes[0].getName());
                    break;
                }
            }

        } catch (Exception e) {
            getLogger().severe("Failed to init reflection: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== NBT Helpers ====================

    private Object unwrapOptional(Object obj) throws Exception {
        if (obj == null) return null;
        try {
            return obj.getClass().getMethod("orElseThrow").invoke(obj);
        } catch (NoSuchMethodException e) {
            return obj;
        }
    }

    private Object loadPlayerData(OfflinePlayer offline) throws Exception {
        return unwrapOptional(playerGetData.invoke(offline));
    }

    private void savePlayerData(UUID uuid, Object rootTag) throws Exception {
        File worldDir = Bukkit.getWorlds().get(0).getWorldFolder();
        File dataDir = new File(worldDir, "playerdata");
        File dataFile = new File(dataDir, uuid + ".dat");
        if (!dataDir.exists()) dataDir.mkdirs();
        nbtIoWrite.invoke(null, rootTag, dataFile.toPath());
    }

    private Object parseNmsItem(Object tag) throws Exception {
        Object dataResult = codecParse.invoke(itemCodec, registryOps, tag);
        Object optional = dataResult.getClass().getMethod("result").invoke(dataResult);
        // Check if parse succeeded; return empty stack if not
        if (!(boolean) optional.getClass().getMethod("isPresent").invoke(optional)) {
            return nmsItemStackEmpty;
        }
        return optional.getClass().getMethod("get").invoke(optional);
    }

    private Object saveNmsItem(Object nmsStack) throws Exception {
        Object dataResult = codecEncode.invoke(itemCodec, registryOps, nmsStack);
        Object optional = dataResult.getClass().getMethod("result").invoke(dataResult);
        if (!(boolean) optional.getClass().getMethod("isPresent").invoke(optional)) {
            return compoundTagClass.getConstructor().newInstance();
        }
        return optional.getClass().getMethod("get").invoke(optional);
    }

    // ==================== NMS Slot Mapping ====================

    // Mirror slot → NBT slot mapping (for offline save)
    private static int nbtSlotToMirror(byte slot) {
        if (slot >= 0 && slot <= 35) return slot;
        if (slot == 100) return 36;
        if (slot == 101) return 37;
        if (slot == 102) return 38;
        if (slot == 103) return 39;
        if (slot == -106) return 40;
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

        String cmd = command.getName().toLowerCase();

        // /enchanttable set - bind the enchanting table the player is looking at
        if ((cmd.equals("enchanttable") || cmd.equals("et")) && args.length > 0 && args[0].equalsIgnoreCase("set")) {
            org.bukkit.block.Block target = player.getTargetBlockExact(5);
            if (target != null && target.getType() == Material.ENCHANTING_TABLE) {
                Location loc = target.getLocation();
                String locStr = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
                boundEnchantTables.put(player.getUniqueId(), locStr);
                saveEnchantBindings();
                player.sendMessage("§a[InvSee] 已绑定附魔台 (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")");
            } else {
                player.sendMessage("§c[InvSee] 请看着一个附魔台使用 /et set");
            }
            return true;
        }

        // Self-use commands
        if (handleSelfCommand(player, cmd)) return true;

        // View other player's inventory/ender chest
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /" + command.getName() + " <player>");
            return true;
        }

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

    // ==================== Online Invsee (Shared Reference via Proxy) ====================

    private void openOnlineInvsee(Player viewer, Player target) {
        try {
            // Get NMS Player for the target (to pass to stillValid)
            Object nmsTarget = target.getClass().getMethod("getHandle").invoke(target);

            // Get the target's NMS PlayerInventory (implements net.minecraft.world.Container)
            Object nmsTargetInv = nmsTarget.getClass().getMethod("getInventory").invoke(nmsTarget);
            Class<?> containerInterface = Class.forName("net.minecraft.world.Container");

            // Glass panes for separator slots (41-44)
            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            glass.editMeta(meta -> meta.displayName(Component.text("§7↑ 装备栏 ↑").decoration(TextDecoration.ITALIC, false)));
            Object nmsGlass = asNMSCopy.invoke(null, glass);

            Component title = Component.text(target.getName() + " 的背包")
                    .color(NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.ITALIC, false);

            // Create a dynamic proxy for Container that delegates to target's PlayerInventory
            Object containerProxy = Proxy.newProxyInstance(
                    containerInterface.getClassLoader(),
                    new Class<?>[]{containerInterface},
                    new GenericContainerHandler(target, nmsTargetInv, nmsItemStackEmpty, nmsGlass, 45)
            );

            // Wrap in CraftInventory and open
            Inventory wrappedInv = (Inventory) craftInventoryCtor.newInstance(containerProxy);

            openInvseeInventories.add(wrappedInv);
            viewer.openInventory(wrappedInv);

        } catch (Exception e) {
            getLogger().warning("Failed to open shared invsee: " + e.getMessage());
            e.printStackTrace();
            viewer.sendMessage("§cFailed to open inventory: " + e.getMessage());
        }
    }

    // ==================== Proxy Handler ====================

    /**
     * Generic dynamic proxy handler for net.minecraft.world.Container.
     * Delegates getItem/setItem to an NMS Container (target's inventory/ender chest).
     * Writes are scheduled on the target's region thread (Folia-safe).
     * Supports optional glass separator slots for inventory view (45 slots).
     */
    private class GenericContainerHandler implements java.lang.reflect.InvocationHandler {
        private final Player targetPlayer;
        private final Object nmsContainer;
        private final Object emptyStack;
        private final Object nmsGlass;
        private final int containerSize;

        private Method containerGetItem;
        private Method containerSetItem;
        private Method containerGetSize;
        private Method containerSetChanged;
        private Method containerStillValid;
        private Method containerStartOpen;
        private Method containerStopOpen;
        private Method containerClearContent;
        private Method containerRemoveItem;
        private Method containerRemoveItemNoUpdate;
        private Method containerGetMaxStackSize;
        private Method containerCanPlaceItem;

        GenericContainerHandler(Player targetPlayer, Object nmsContainer,
                                Object emptyStack, Object nmsGlass, int containerSize) throws Exception {
            this.targetPlayer = targetPlayer;
            this.nmsContainer = nmsContainer;
            this.emptyStack = emptyStack;
            this.nmsGlass = nmsGlass;
            this.containerSize = containerSize;

            // Find methods by name (bypass ReflectionRewriter which breaks getMethod)
            for (java.lang.reflect.Method m : nmsContainer.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                switch (m.getName()) {
                    case "getItem":
                        if (p.length == 1 && p[0] == int.class) containerGetItem = m; break;
                    case "setItem":
                        if (p.length == 2 && p[0] == int.class) containerSetItem = m; break;
                    case "getContainerSize":
                        if (p.length == 0) containerGetSize = m; break;
                    case "setChanged":
                        if (p.length == 0) containerSetChanged = m; break;
                    case "stillValid":
                        if (p.length == 1) containerStillValid = m; break;
                    case "startOpen":
                        if (p.length == 1) containerStartOpen = m; break;
                    case "stopOpen":
                        if (p.length == 1) containerStopOpen = m; break;
                    case "clearContent":
                        if (p.length == 0) containerClearContent = m; break;
                    case "removeItem":
                        if (p.length == 2 && p[0] == int.class) containerRemoveItem = m; break;
                    case "removeItemNoUpdate":
                        if (p.length == 1 && p[0] == int.class) containerRemoveItemNoUpdate = m; break;
                    case "getMaxStackSize":
                        if (p.length == 0) containerGetMaxStackSize = m; break;
                    case "canPlaceItem":
                        if (p.length == 2 && p[0] == int.class) containerCanPlaceItem = m; break;
                }
            }
        }

        private Object getGlass(int index) {
            return nmsGlass;
        }

        private boolean isGlassSlot(int slot) {
            return nmsGlass != null && slot >= 41 && slot <= 44;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Class<?>[] params = method.getParameterTypes();

            // ==================== Read operations ====================

            if (name.equals("getItem") && params.length == 1 && params[0] == int.class) {
                int slot = (int) args[0];
                if (slot == -1) return emptyStack;
                if (isGlassSlot(slot)) return nmsGlass;
                return containerGetItem.invoke(nmsContainer, slot);
            }

            if (name.equals("removeItem") && params.length == 2) {
                int slot = (int) args[0];
                if (isGlassSlot(slot)) return emptyStack;
                return containerRemoveItem.invoke(nmsContainer, args);
            }

            if (name.equals("removeItemNoUpdate") && params.length == 1) {
                int slot = (int) args[0];
                if (isGlassSlot(slot)) return emptyStack;
                return containerRemoveItemNoUpdate.invoke(nmsContainer, slot);
            }

            if (name.equals("isEmpty")) {
                return false;
            }

            if (name.equals("getContainerSize")) {
                return containerSize;
            }

            if (name.equals("getMaxStackSize")) {
                return containerGetMaxStackSize.invoke(nmsContainer);
            }

            if (name.equals("stillValid") && params.length == 1) {
                return true;
            }

            // ==================== Write operations ====================

            if (name.equals("setItem") && params.length == 2 && params[0] == int.class) {
                int slot = (int) args[0];
                Object stack = args[1];
                if (slot == -1 || isGlassSlot(slot)) return null;
                int finalSlot = slot;
                Object finalStack = stack;
                targetPlayer.getScheduler().run(InvSee.this, t -> {
                    try {
                        containerSetItem.invoke(nmsContainer, finalSlot, finalStack);
                        containerSetChanged.invoke(nmsContainer);
                    } catch (Exception e) {
                        getLogger().warning("Failed to set item on slot " + finalSlot + ": " + e.getMessage());
                    }
                }, null);
                return null;
            }

            if (name.equals("setChanged")) {
                containerSetChanged.invoke(nmsContainer);
                return null;
            }

            if (name.equals("startOpen") || name.equals("stopOpen")) {
                try {
                    method.invoke(nmsContainer, args);
                } catch (Exception ignored) {}
                return null;
            }

            if (name.equals("clearContent")) {
                containerClearContent.invoke(nmsContainer);
                return null;
            }

            if (name.equals("canPlaceItem") && params.length == 2) {
                int slot = (int) args[0];
                if (isGlassSlot(slot)) return false;
                return containerCanPlaceItem.invoke(nmsContainer, args);
            }

            // Default: return safe defaults for primitive types to avoid NPE
            Class<?> returnType = method.getReturnType();
            if (returnType == int.class || returnType == long.class
                    || returnType == short.class || returnType == byte.class) return 0;
            if (returnType == boolean.class) return false;
            if (returnType == float.class || returnType == double.class) return 0.0;
            if (returnType == void.class) return null;
            return null;
        }
    }

    // ==================== Online EnderChest (Direct Reference) ====================

    private void openOnlineEnderChest(Player viewer, Player target) {
        try {
            Object nmsTarget = target.getClass().getMethod("getHandle").invoke(target);
            // Get the target's NMS EnderChestInventory (implements net.minecraft.world.Container)
            Object nmsEnderChest = nmsTarget.getClass().getMethod("getEnderChestInventory").invoke(nmsTarget);
            Class<?> containerInterface = Class.forName("net.minecraft.world.Container");

            Component title = Component.text(target.getName() + " 的末影箱")
                    .color(NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.ITALIC, false);

            // Create proxy for Container that delegates to target's EnderChestInventory
            // with writes scheduled on target's region thread (no glass panes, size 27)
            Object containerProxy = Proxy.newProxyInstance(
                    containerInterface.getClassLoader(),
                    new Class<?>[]{containerInterface},
                    new GenericContainerHandler(target, nmsEnderChest, nmsItemStackEmpty, null, 27)
            );

            Inventory wrappedInv = (Inventory) craftInventoryCtor.newInstance(containerProxy);
            openInvseeInventories.add(wrappedInv);
            viewer.openInventory(wrappedInv);

        } catch (Exception e) {
            getLogger().warning("Failed to open shared ender chest: " + e.getMessage());
            e.printStackTrace();
            viewer.sendMessage("§cFailed to open ender chest: " + e.getMessage());
        }
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
                    byte slot = (byte) unwrapOptional(
                            compoundTagClass.getMethod("getByte", String.class).invoke(itemTag, "Slot"));
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
            offlineInvViewers.put(mirror, viewer);
            viewer.openInventory(mirror);

        } catch (Exception e) {
            getLogger().warning("Failed loading offline inventory for " + targetName + ": " + e.getMessage());
            e.printStackTrace();
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
            // Use getMethods() loop to avoid ReflectionRewriter issues with generic add(Tag)
            Method listAdd = null;
            for (Method m : listTagClass.getMethods()) {
                if (m.getName().equals("add") && m.getParameterCount() == 1) {
                    listAdd = m;
                    break;
                }
            }

            if (oldInvList != null) {
                int oldSize = (int) oldInvList.getClass().getMethod("size").invoke(oldInvList);
                for (int i = 0; i < oldSize; i++) {
                    Object itemTag = unwrapOptional(
                            listTagClass.getMethod("getCompound", int.class).invoke(oldInvList, i));
                    byte slot = (byte) unwrapOptional(
                            compoundTagClass.getMethod("getByte", String.class).invoke(itemTag, "Slot"));
                    if (isManagedSlot(slot)) continue;
                    listAdd.invoke(newList, itemTag);
                }
            }

            for (int mirrorSlot = 0; mirrorSlot < 41; mirrorSlot++) {
                ItemStack item = mirror.getItem(mirrorSlot);
                int nbtSlot = mirrorSlotToNbt(mirrorSlot);
                if (nbtSlot == Integer.MIN_VALUE) continue;
                if (item == null || item.getType().isEmpty()) continue;
                // Never save glass separator items
                if (item.getType() == Material.GRAY_STAINED_GLASS_PANE) continue;
                Object nmsStack = asNMSCopy.invoke(null, item);
                Object tag = saveNmsItem(nmsStack);
                compoundTagClass.getMethod("putByte", String.class, byte.class).invoke(tag, "Slot", (byte) nbtSlot);
                listAdd.invoke(newList, tag);
            }

            // Use getMethods() loop for put(String, Tag) to avoid ReflectionRewriter generic erasure
            Method put = null;
            for (Method m : compoundTagClass.getMethods()) {
                if (m.getName().equals("put") && m.getParameterCount() == 2) {
                    put = m;
                    break;
                }
            }
            put.invoke(rootTag, "Inventory", newList);
            savePlayerData(uuid, rootTag);

        } catch (Exception e) {
            getLogger().warning("Failed saving offline inventory for " + uuid + ": " + e.getMessage());
            e.printStackTrace();
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
            offlineEnderViewers.put(inv, viewer);
            viewer.openInventory(inv);

        } catch (Exception e) {
            getLogger().warning("Failed loading offline ender chest for " + targetName + ": " + e.getMessage());
            e.printStackTrace();
            viewer.sendMessage("§cFailed to load player data: " + e.getMessage());
        }
    }

    private void saveOfflineEnderChest(Inventory inv, UUID uuid) {
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            Object rootTag = loadPlayerData(offline);

            Object newList = listTagClass.getConstructor().newInstance();
            // Use getMethods() loop to avoid ReflectionRewriter issues with generic add(Tag)
            Method listAdd = null;
            for (Method m : listTagClass.getMethods()) {
                if (m.getName().equals("add") && m.getParameterCount() == 1) {
                    listAdd = m;
                    break;
                }
            }

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
            e.printStackTrace();
        }
    }

    // ==================== Enchanting Table Bindings ====================

    private void loadEnchantBindings() {
        bindingsFile = new File(getDataFolder(), "enchant_bindings.yml");
        if (bindingsFile.exists()) {
            bindingsConfig = YamlConfiguration.loadConfiguration(bindingsFile);
            for (String key : bindingsConfig.getKeys(false)) {
                try {
                    boundEnchantTables.put(java.util.UUID.fromString(key), bindingsConfig.getString(key));
                } catch (Exception ignored) {}
            }
        } else {
            bindingsConfig = new YamlConfiguration();
        }
    }

    private void saveEnchantBindings() {
        try {
            for (java.util.Map.Entry<java.util.UUID, String> e : boundEnchantTables.entrySet()) {
                bindingsConfig.set(e.getKey().toString(), e.getValue());
            }
            bindingsConfig.save(bindingsFile);
        } catch (Exception e) {
            getLogger().warning("Failed to save enchant bindings: " + e.getMessage());
        }
    }

    /** Try to open a bound enchanting table for the player */
    private boolean openBoundEnchantTable(Player player) {
        String locStr = boundEnchantTables.get(player.getUniqueId());
        if (locStr == null) return false;
        String[] parts = locStr.split(",");
        if (parts.length != 4) return false;
        try {
            org.bukkit.World world = Bukkit.getWorld(parts[0]);
            if (world == null) return false;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            // 检查绑定位置是否真的有附魔台
            if (world.getBlockAt(x, y, z).getType() != Material.ENCHANTING_TABLE) {
                return false;
            }
            player.openEnchanting(new Location(world, x + 0.5, y, z + 0.5), true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Self-Use Commands ====================

    private boolean handleSelfCommand(Player player, String cmd) {
        String perm = "invsee." + cmd;
        if (!player.hasPermission(perm)) {
            player.sendMessage("§cYou don't have permission.");
            return true;
        }

        player.getScheduler().run(this, task -> {
            switch (cmd) {
                case "enderchest":
                    player.openInventory(player.getEnderChest()); break;
                case "workbench":
                    player.openWorkbench(null, true); break;
                case "anvil":
                    player.openAnvil(null, true); break;
                case "grindstone":
                    player.openGrindstone(null, true); break;
                case "enchanttable": {
                    if (openBoundEnchantTable(player)) break;
                    player.sendMessage("§c[InvSee] 请先绑定工作台: 看着附魔台输入 /et set");
                    break;
                }
                case "stonecutter":
                    player.openStonecutter(null, true); break;
                case "cartography":
                    player.openCartographyTable(null, true); break;
                case "loom":
                    player.openLoom(null, true); break;
            }
        }, null);
        return true;
    }

    // ==================== Player Join (conflict prevention) ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        UUID joinedUuid = joined.getUniqueId();

        // Check if any admin is viewing this player's offline inventory (copy to avoid CME)
        List<Inventory> toClose = new ArrayList<>();
        for (Map.Entry<Inventory, UUID> entry : new ArrayList<>(offlineInvTargets.entrySet())) {
            if (entry.getValue().equals(joinedUuid)) {
                Player viewer = offlineInvViewers.get(entry.getKey());
                if (viewer != null && viewer.isOnline()) {
                    viewer.sendMessage("§c[InvSee] " + joined.getName() + " 已上线，离线背包视图已关闭");
                    toClose.add(entry.getKey());
                }
            }
        }
        for (Inventory inv : toClose) {
            Player viewer = offlineInvViewers.get(inv);
            if (viewer != null) viewer.closeInventory();
        }
        for (Map.Entry<Inventory, UUID> entry : new ArrayList<>(offlineEnderTargets.entrySet())) {
            if (entry.getValue().equals(joinedUuid)) {
                Player viewer = offlineEnderViewers.get(entry.getKey());
                if (viewer != null && viewer.isOnline()) {
                    viewer.sendMessage("§c[InvSee] " + joined.getName() + " 已上线，离线末影箱视图已关闭");
                    toClose.add(entry.getKey());
                }
            }
        }
        for (Inventory inv : toClose) {
            Player viewer = offlineEnderViewers.get(inv);
            if (viewer != null) viewer.closeInventory();
        }
    }

    // ==================== Inventory Events ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean isOurs = offlineInvTargets.containsKey(top)
                || offlineEnderTargets.containsKey(top)
                || openInvseeInventories.contains(top);

        // Protect glass separator slots in ALL our inventories
        if (event.getSlot() >= 41 && event.getSlot() <= 44) {
            event.setCancelled(true);
            return;
        }

        if (!isOurs) return;

        if (!event.getWhoClicked().hasPermission("invsee.modify")) {
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean isOurs = offlineInvTargets.containsKey(top)
                || offlineEnderTargets.containsKey(top)
                || openInvseeInventories.contains(top);

        if (isOurs) {
            // Cancel drag if it involves glass separator slots
            for (int slot : event.getInventorySlots()) {
                if (slot >= 41 && slot <= 44) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (!event.getWhoClicked().hasPermission("invsee.modify")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();

        // Offline invsee: save to NBT file (or apply to live player if online)
        UUID invUuid = offlineInvTargets.remove(inv);
        offlineInvViewers.remove(inv);
        if (invUuid != null) {
            Player livePlayer = Bukkit.getPlayer(invUuid);
            if (livePlayer != null && livePlayer.isOnline()) {
                // Player is now online: apply changes directly to live inventory
                for (int i = 0; i < 36; i++) livePlayer.getInventory().setItem(i, inv.getItem(i));
                for (int i = 0; i < 4; i++) livePlayer.getInventory().setItem(36 + i, inv.getItem(36 + i));
                livePlayer.getInventory().setItemInOffHand(inv.getItem(40));
            } else {
                saveOfflineInvsee(inv, invUuid);
            }
            return;
        }

        // Offline ender: save to NBT file
        UUID enderUuid = offlineEnderTargets.remove(inv);
        offlineEnderViewers.remove(inv);
        if (enderUuid != null) {
            saveOfflineEnderChest(inv, enderUuid);
        }

        // Online invsee/endersee: cleanup tracking
        openInvseeInventories.remove(inv);
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
