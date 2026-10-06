package com.example.stasis;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Atılan ender pearl'in bulunduğu chunk'ı yüklü tutar.
 * Pearl bir yere çarpıp teleport olursa ya da yok olursa chunk serbest bırakılır.
 * Stasis chamber'daki pearl çarpmadan asılı kaldığı için chunk yüklü kalır.
 */
public class StasisPlugin extends JavaPlugin implements Listener, CommandExecutor {

    /** Bir oyuncunun aynı anda yüklü tutabileceği en fazla pearl sayısı. */
    private static final int MAX_PER_PLAYER = 3;
    /** Pearl bu kadar saniye havada/duruyor kalırsa oyuncuya bilgi verilir. */
    private static final int NOTIFY_AFTER_SECONDS = 10;

    private static class Entry {
        UUID owner;
        String world;
        int cx;
        int cz;
        int age;
    }

    private final Map<UUID, Entry> pearls = new HashMap<>();
    private final Map<String, Integer> chunkRefs = new HashMap<>();
    private File file;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        file = new File(getDataFolder(), "pearls.yml");
        loadFromFile();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("stasis").setExecutor(this);

        // Açılıştan 10 saniye sonra başla, her saniye kontrol et.
        // (Başta beklememizin sebebi: pearl entity'lerinin yüklenmesine zaman tanımak)
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 200L, 20L);
        getLogger().info("StasisLoader aktif! Yüklü tutulan pearl: " + pearls.size());
    }

    // ---------------------------------------------------------------
    // Chunk ticket yönetimi (aynı chunk'ta birden fazla pearl olabilir)
    // ---------------------------------------------------------------

    private String key(String world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    private void addRef(String worldName, int cx, int cz) {
        int n = chunkRefs.merge(key(worldName, cx, cz), 1, Integer::sum);
        if (n == 1) {
            World w = Bukkit.getWorld(worldName);
            if (w != null) w.addPluginChunkTicket(cx, cz, this);
        }
    }

    private void removeRef(String worldName, int cx, int cz) {
        String k = key(worldName, cx, cz);
        Integer n = chunkRefs.get(k);
        if (n == null) return;
        if (n <= 1) {
            chunkRefs.remove(k);
            World w = Bukkit.getWorld(worldName);
            if (w != null) w.removePluginChunkTicket(cx, cz, this);
        } else {
            chunkRefs.put(k, n - 1);
        }
    }

    private void untrack(UUID pearlId) {
        Entry e = pearls.remove(pearlId);
        if (e != null) {
            removeRef(e.world, e.cx, e.cz);
            saveToFile();
        }
    }

    // ---------------------------------------------------------------
    // Olaylar
    // ---------------------------------------------------------------

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player p)) return;

        long count = pearls.values().stream()
                .filter(x -> x.owner.equals(p.getUniqueId())).count();
        if (count >= MAX_PER_PLAYER) return;

        Location l = pearl.getLocation();
        Entry en = new Entry();
        en.owner = p.getUniqueId();
        en.world = l.getWorld().getName();
        en.cx = l.getBlockX() >> 4;
        en.cz = l.getBlockZ() >> 4;

        pearls.put(pearl.getUniqueId(), en);
        addRef(en.world, en.cx, en.cz);
        saveToFile();
    }

    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        // Pearl bir şeye çarptı = teleport oldu, artık stasis değil
        if (e.getEntity() instanceof EnderPearl pearl) {
            untrack(pearl.getUniqueId());
        }
    }

    // ---------------------------------------------------------------
    // Her saniye: pearl hâlâ var mı, başka chunk'a geçti mi?
    // ---------------------------------------------------------------

    private void tick() {
        for (UUID id : new ArrayList<>(pearls.keySet())) {
            Entry en = pearls.get(id);
            if (en == null) continue;

            Entity ent = Bukkit.getEntity(id);
            if (ent == null || !ent.isValid()) {
                World w = Bukkit.getWorld(en.world);
                if (w == null || w.isChunkLoaded(en.cx, en.cz)) {
                    untrack(id); // chunk yüklü ama pearl yok = pearl yok olmuş
                }
                continue;
            }

            Location l = ent.getLocation();
            String wn = l.getWorld().getName();
            int cx = l.getBlockX() >> 4;
            int cz = l.getBlockZ() >> 4;
            if (cx != en.cx || cz != en.cz || !wn.equals(en.world)) {
                addRef(wn, cx, cz);                 // önce yenisini yükle
                removeRef(en.world, en.cx, en.cz);  // sonra eskisini bırak
                en.world = wn;
                en.cx = cx;
                en.cz = cz;
                saveToFile();
            }

            en.age++;
            if (en.age == NOTIFY_AFTER_SECONDS) {
                Player owner = Bukkit.getPlayer(en.owner);
                if (owner != null) {
                    owner.sendMessage("§aPearl'inin bulunduğu chunk artık yüklü tutuluyor. §7(/stasis ile listele)");
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // /stasis : kendi pearl'lerini listele
    // ---------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Yüklü tutulan toplam pearl: " + pearls.size());
            return true;
        }
        int n = 0;
        for (Entry en : pearls.values()) {
            if (!en.owner.equals(p.getUniqueId())) continue;
            n++;
            p.sendMessage("§e" + n + ". §f" + en.world + " §7- chunk bölgesi x≈" + (en.cx * 16) + " z≈" + (en.cz * 16));
        }
        if (n == 0) {
            p.sendMessage("§cYüklü tutulan pearl'in yok.");
        } else {
            p.sendMessage("§7En fazla " + MAX_PER_PLAYER + " pearl aynı anda yüklü tutulur.");
        }
        return true;
    }

    // ---------------------------------------------------------------
    // Kayıt (sunucu yeniden başlayınca chunk'lar tekrar yüklenir)
    // ---------------------------------------------------------------

    private void saveToFile() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> me : pearls.entrySet()) {
            String p = "pearls." + me.getKey();
            Entry en = me.getValue();
            yml.set(p + ".owner", en.owner.toString());
            yml.set(p + ".world", en.world);
            yml.set(p + ".cx", en.cx);
            yml.set(p + ".cz", en.cz);
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            getLogger().severe("pearls.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    private void loadFromFile() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("pearls");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            try {
                Entry en = new Entry();
                en.owner = UUID.fromString(sec.getString(id + ".owner"));
                en.world = sec.getString(id + ".world");
                en.cx = sec.getInt(id + ".cx");
                en.cz = sec.getInt(id + ".cz");
                pearls.put(UUID.fromString(id), en);
                addRef(en.world, en.cx, en.cz);
            } catch (Exception ex) {
                getLogger().warning("Bozuk kayıt atlandı: " + id);
            }
        }
    }
}
