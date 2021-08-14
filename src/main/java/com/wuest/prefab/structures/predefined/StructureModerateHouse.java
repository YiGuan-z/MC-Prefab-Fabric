package com.wuest.prefab.structures.predefined;

import com.wuest.prefab.ModRegistry;
import com.wuest.prefab.Prefab;

import com.wuest.prefab.Tuple;
import com.wuest.prefab.config.EntityPlayerConfiguration;
import com.wuest.prefab.network.message.ConfigSyncMessage;
import com.wuest.prefab.network.message.PlayerEntityTagMessage;
import com.wuest.prefab.structures.base.BuildBlock;
import com.wuest.prefab.structures.base.BuildClear;
import com.wuest.prefab.structures.base.BuildingMethods;
import com.wuest.prefab.structures.base.Structure;
import com.wuest.prefab.structures.config.ModerateHouseConfiguration;
import com.wuest.prefab.structures.config.StructureConfiguration;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry;
import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;


import java.util.ArrayList;

/**
 * @author WuestMan
 */
public class StructureModerateHouse extends Structure {
	private BlockPos chestPosition = null;
	private ArrayList<BlockPos> furnacePosition = null;
	private BlockPos trapDoorPosition = null;
	private ArrayList<Tuple<BlockPos, BlockPos>> bedPositions = new ArrayList<>();

	public static void ScanStructure(World world, BlockPos originalPos, Direction playerFacing, ModerateHouseConfiguration.HouseStyle houseStyle) {
		BuildClear clearedSpace = new BuildClear();
		clearedSpace.getShape().setDirection(playerFacing);
		clearedSpace.getShape().setHeight(houseStyle.getHeight());
		clearedSpace.getShape().setLength(houseStyle.getLength() + 1);
		clearedSpace.getShape().setWidth(houseStyle.getWidth());
		clearedSpace.getStartingPosition().setHorizontalOffset(playerFacing, 1);

		// East is always to the "Left" of the player.
		clearedSpace.getStartingPosition().setHorizontalOffset(playerFacing.rotateYCounterclockwise(), houseStyle.getEastOffSet());
		clearedSpace.getStartingPosition().setHeightOffset(houseStyle.getDownOffSet() * -1);

		BlockPos cornerPos = originalPos.offset(playerFacing.rotateYCounterclockwise(), houseStyle.getEastOffSet()).offset(playerFacing).down(houseStyle.getDownOffSet());

		Structure.ScanStructure(
				world,
				originalPos,
				cornerPos,
				cornerPos.offset(playerFacing, houseStyle.getLength()).offset(playerFacing.rotateYClockwise(), houseStyle.getWidth()).up(houseStyle.getHeight()),
				"../src/main/resources/" + houseStyle.getStructureLocation(),
				clearedSpace,
				playerFacing, false, false);
	}

	@Override
	protected Boolean CustomBlockProcessingHandled(StructureConfiguration configuration, BuildBlock block, World world, BlockPos originalPos,
												   Direction assumedNorth, Block foundBlock, BlockState blockState, PlayerEntity player) {

		ModerateHouseConfiguration houseConfiguration = (ModerateHouseConfiguration) configuration;

		if (foundBlock instanceof FurnaceBlock) {
			if (this.furnacePosition == null) {
				this.furnacePosition = new ArrayList<BlockPos>();
			}

			this.furnacePosition.add(block.getStartingPosition().getRelativePosition(
					originalPos,
					this.getClearSpace().getShape().getDirection(),
					configuration.houseFacing));
		} else if (foundBlock instanceof ChestBlock && !houseConfiguration.addChests) {
			return true;
		} else if (foundBlock instanceof ChestBlock && this.chestPosition == null) {
			this.chestPosition = block.getStartingPosition().getRelativePosition(
					originalPos,
					this.getClearSpace().getShape().getDirection(),
					configuration.houseFacing);
		} else if (foundBlock instanceof TrapdoorBlock && this.trapDoorPosition == null) {
			// The trap door will still be added, but the mine shaft may not be
			// built.
			this.trapDoorPosition = block.getStartingPosition().getRelativePosition(
					originalPos,
					this.getClearSpace().getShape().getDirection(),
					configuration.houseFacing);
		} else if (foundBlock == Blocks.SPONGE) {
			// Sponges are sometimes used in-place of trapdoors when trapdoors are used for decoration.
			this.trapDoorPosition = block.getStartingPosition().getRelativePosition(
					originalPos,
					this.getClearSpace().getShape().getDirection(),
					configuration.houseFacing).up();
		} else if (foundBlock instanceof BedBlock) {
			BlockPos bedHeadPosition = block.getStartingPosition().getRelativePosition(originalPos, this.getClearSpace().getShape().getDirection(), configuration.houseFacing);
			BlockPos bedFootPosition = block.getSubBlock().getStartingPosition().getRelativePosition(
					originalPos,
					this.getClearSpace().getShape().getDirection(),
					configuration.houseFacing);

			this.bedPositions.add(new Tuple<>(bedHeadPosition, bedFootPosition));

			return true;
		}

		return false;
	}

	/**
	 * This method is used after the main building is build for any additional structures or modifications.
	 *
	 * @param configuration The structure configuration.
	 * @param world         The current world.
	 * @param originalPos   The original position clicked on.
	 * @param assumedNorth  The assumed northern direction.
	 * @param player        The player which initiated the construction.
	 */
	@Override
	public void AfterBuilding(StructureConfiguration configuration, ServerWorld world, BlockPos originalPos, Direction assumedNorth, PlayerEntity player) {
		ModerateHouseConfiguration houseConfig = (ModerateHouseConfiguration) configuration;
		EntityPlayerConfiguration playerConfig = EntityPlayerConfiguration.loadFromEntity(player);

		BuildingMethods.FillFurnaces(world, this.furnacePosition);

		if (this.chestPosition != null && !playerConfig.builtStarterHouse && houseConfig.addChestContents) {
			// Fill the chest if the player hasn't generated the starting house yet.
			BuildingMethods.FillChest(world, this.chestPosition);
		}

		if (this.trapDoorPosition != null && this.trapDoorPosition.getY() > 15 && houseConfig.addMineshaft) {
			// Build the mineshaft.
			BuildingMethods.PlaceMineShaft(world, this.trapDoorPosition.down(), houseConfig.houseFacing, false);
		}

		if (this.bedPositions.size() > 0) {
			for (Tuple<BlockPos, BlockPos> bedPosition : this.bedPositions) {
				BuildingMethods.PlaceColoredBed(world, bedPosition.getFirst(), bedPosition.getSecond(), houseConfig.bedColor);
			}
		}

		// Make sure to set this value so the player cannot fill the chest a second time.
		playerConfig.builtStarterHouse = true;

		PlayerEntityTagMessage message = new PlayerEntityTagMessage();
		message.setMessageTag(playerConfig.createPlayerTag());
		PacketByteBuf byteBuf = new PacketByteBuf(Unpooled.buffer());

		PlayerEntityTagMessage.encode(message, byteBuf);

		// Make sure to send a message to the client to sync up the server player information and the client player
		// information.
		ServerSidePacketRegistry.INSTANCE.sendToPlayer(player, ModRegistry.PlayerConfigSync, byteBuf);
	}

}