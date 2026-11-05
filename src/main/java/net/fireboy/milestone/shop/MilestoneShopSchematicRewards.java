package net.fireboy.milestone.shop;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.foundation.utility.CreatePaths;
import net.fireboy.milestone.milestone.ConfiguredSchematic;
import net.fireboy.milestone.network.CreateSchematicNetwork;
import net.fireboy.milestone.server.config.MilestoneContentConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/** Real Create filled schematic rewards from the OP-managed Milestone library.
 *
 * We use a shared owner directory, deterministic offer+content filenames and
 * validated paths. A content hash preserves previously sold templates if an
 * OP changes the original .nbt file later. The buyer receives the local copy
 * through the same blueprint sync as Mission Station schematic claims.
 */
public final class MilestoneShopSchematicRewards {
    private static final Logger LOG=LoggerFactory.getLogger(MilestoneShopSchematicRewards.class);
    private static final String OWNER="milestone-shop";
    private static final int MAX_BYTES=262144;
    // Only players possessing a real filled Create schematic receive its
    // bytes on reconnect or after trading; others never get paid blueprints.
    private static final Map<UUID,Set<String>> SESSION_SYNCED=new HashMap<>();
    private static final java.util.regex.Pattern SHOP_NAME=
            java.util.regex.Pattern.compile("milestone-shop-[a-z0-9_-]{1,64}-[0-9a-f]{16}\\.nbt");

    public record PreparedReward(ItemStack stack,String filename,byte[] bytes) {
        public void sendTo(ServerPlayer buyer) {
            CreateSchematicNetwork.send(buyer,filename,bytes);
            SESSION_SYNCED.computeIfAbsent(buyer.getUUID(),ignored->new HashSet<>()).add(filename);
        }
    }

    private MilestoneShopSchematicRewards() {}

    private static Path libraryRoot() {
        return CreatePaths.UPLOADED_SCHEMATICS_DIR.toAbsolutePath().normalize();
    }
    private static String filename(MilestoneShop.Offer offer,byte[] bytes) throws IOException {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder digest=new StringBuilder(16);
            for(int i=0;i<8;i++)digest.append(String.format("%02x",hash[i]&255));
            return "milestone-shop-"+offer.id()+"-"+digest+".nbt";
        }catch(NoSuchAlgorithmException e){
            throw new IOException("Unable to hash Create schematic",e);
        }
    }

    public static PreparedReward prepare(ServerPlayer player,MilestoneShop.Offer offer) throws IOException {
        if(!offer.rewardType().equals("schematic"))throw new IOException("Not a schematic offer");
        var entry=MilestoneContentConfig.schematic(MilestoneContentConfig.server(),
                offer.schematicId());
        if(entry==null)throw new IOException("Missing configured schematic "+offer.schematicId());
        // The imported library validator restricts file paths, dimensions,
        // unsafe block entities, command blocks and the unbreakable Core.
        byte[] bytes=ConfiguredSchematic.readFile(entry.file()).bytes();
        String name=filename(offer,bytes);
        Path root=libraryRoot();
        Path folder=root.resolve(OWNER).normalize();
        if(!folder.getParent().equals(root))throw new IOException("Invalid Create folder");
        Files.createDirectories(folder);
        Path file=folder.resolve(name).normalize();
        if(!file.getParent().equals(folder) || Files.isSymbolicLink(file))
            throw new IOException("Invalid Create schematic destination");
        if(!Files.isRegularFile(file) || !Arrays.equals(bytes,Files.readAllBytes(file)))
            Files.write(file,bytes);
        ItemStack stack=SchematicItem.create(player.level(),name,OWNER);
        if(stack.isEmpty())throw new IOException("Create failed to create filled schematic item");
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(offer.name())
                .withStyle(ChatFormatting.AQUA));
        // The bytes are not transferred until AFTER the server confirms
        // that all payment resources were deducted successfully.
        return new PreparedReward(stack,name,bytes);
    }

    private static void syncPlayerInventory(ServerPlayer player) {
        Set<String> synced=SESSION_SYNCED.computeIfAbsent(player.getUUID(),
                ignored->new HashSet<>());
        for(int index=0;index<player.getInventory().getContainerSize();index++)
            syncIfOwned(player,player.getInventory().getItem(index),synced);
        for(int index=0;index<player.getEnderChestInventory().getContainerSize();index++)
            syncIfOwned(player,player.getEnderChestInventory().getItem(index),synced);
    }

    private static void syncIfOwned(ServerPlayer player,ItemStack stack,Set<String> synced){
        if(stack.isEmpty()||!(stack.getItem() instanceof SchematicItem))return;
        String file=stack.get(AllDataComponents.SCHEMATIC_FILE);
        if(file==null || !SHOP_NAME.matcher(file).matches() || synced.contains(file))return;
        Path ownerFolder=libraryRoot().resolve(OWNER).normalize();
        Path path=ownerFolder.resolve(file).normalize();
        if(!path.getParent().equals(ownerFolder)||Files.isSymbolicLink(ownerFolder)
                ||!Files.isRegularFile(path)||Files.isSymbolicLink(path))return;
        try{
            long length=Files.size(path);
            if(length<1||length>MAX_BYTES)return;
            CreateSchematicNetwork.send(player,file,Files.readAllBytes(path));
            synced.add(file);
        }catch(IOException ex){
            LOG.warn("Could not sync traded shop schematic {}",file,ex);
        }
    }

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        SESSION_SYNCED.remove(player.getUUID());
        syncPlayerInventory(player);
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if(event.getEntity() instanceof ServerPlayer player && player.tickCount%40==0)
            syncPlayerInventory(player);
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player)
            SESSION_SYNCED.remove(player.getUUID());
    }
}
