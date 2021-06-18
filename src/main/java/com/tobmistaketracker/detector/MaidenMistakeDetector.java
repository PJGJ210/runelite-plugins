package com.tobmistaketracker.detector;

import com.tobmistaketracker.TobBossNames;
import com.tobmistaketracker.TobMistake;
import com.tobmistaketracker.TobMistakeTrackerPlugin;
import com.tobmistaketracker.TobRaider;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listen, I know this is jank, but I think it works... I'm open to suggestions for better ways of detecting this.
 * <p>
 * Another option would be to try more to mock the actual server logic, and perform certain calculations on the
 * tick before the damage/mistake gets sent to the client, but I think this is easier/simpler.
 * <p>
 * I might get annoyed enough of having to track "previous tick" metadata that I'll re-write all this anyway though.
 */
@Slf4j
@Singleton
public class MaidenMistakeDetector implements TobMistakeDetector {

    private static final int BLOOD_SPAWN_BLOOD_GAME_OBJECT_ID = 32984;
    private static final int MAIDEN_BLOOD_GRAPHICS_OBJECT_ID = 1579;

    private static final int CYCLES_PER_GAME_TICK = Constants.GAME_TICK_LENGTH / Constants.CLIENT_TICK_LENGTH;

    // Sometimes the activation calculation is actually off by a few cycles. We'll account for that with an offset.
    private static final int BLOOD_SPAWN_ACTIVATION_CYCLE_OFFSET = 5;

    // It's easier to track these separately and check if player is in either of them, since they can overlap and
    // we don't need to worry about removing one accidentally when the other despawns. They're also different objects.
    @Getter
    private final Set<WorldPoint> bloodSpawnBloodTiles;
    @Getter
    private final Map<WorldPoint, GraphicsObject> maidenBloodTiles;
    private final List<GraphicsObject> maidenBloodGraphicsObjects;

    // From what I can tell, we need to remove the blood spawn tiles *after* we detect for that tick, so aggregate here
    private final Set<WorldPoint> bloodSpawnBloodTilesToRemove;

    private final TobMistakeTrackerPlugin plugin;

    private final Client client;

    @Getter
    private boolean detectingMistakes;

    @Inject
    public MaidenMistakeDetector(TobMistakeTrackerPlugin plugin, Client client) {
        this.plugin = plugin;
        this.client = client;

        bloodSpawnBloodTiles = new HashSet<>();
        bloodSpawnBloodTilesToRemove = new HashSet<>();
        maidenBloodTiles = new HashMap<>();
        maidenBloodGraphicsObjects = new ArrayList<>();
    }

    @Override
    public void startup() {
        detectingMistakes = true;
    }

    @Override
    public void shutdown() {
        bloodSpawnBloodTiles.clear();
        bloodSpawnBloodTilesToRemove.clear();
        maidenBloodTiles.clear();
        maidenBloodGraphicsObjects.clear();

        detectingMistakes = false;
    }

    @Override
    public List<TobMistake> detectMistakes(@NonNull TobRaider raider) {
        if (!raider.isPreviousIsDead() && isOnBloodTile(raider.getPreviousWorldLocation())) {
            return Collections.singletonList(TobMistake.MAIDEN_BLOOD);
        }

        return Collections.emptyList();
    }

    @Override
    public void afterDetect() {
        // Remove the blood spawn blood tiles *after* detecting this tick, so that it's still around for detection.
        bloodSpawnBloodTiles.removeAll(bloodSpawnBloodTilesToRemove);
        bloodSpawnBloodTilesToRemove.clear();
    }

    private boolean isOnBloodTile(WorldPoint worldPoint) {
        return bloodSpawnBloodTiles.contains(worldPoint) || maidenBloodTiles.containsKey(worldPoint);
    }

    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        GraphicsObject go = event.getGraphicsObject();
        if (go.getId() == MAIDEN_BLOOD_GRAPHICS_OBJECT_ID) {
            maidenBloodGraphicsObjects.add(go);
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject go = event.getGameObject();
        if (go.getId() == BLOOD_SPAWN_BLOOD_GAME_OBJECT_ID) {
            bloodSpawnBloodTiles.add(go.getWorldLocation());
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject go = event.getGameObject();
        if (go.getId() == BLOOD_SPAWN_BLOOD_GAME_OBJECT_ID) {
            // Remove these *after* detecting this tick, since they were still present in the previous player location.
            bloodSpawnBloodTilesToRemove.add(go.getWorldLocation());
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        Actor actor = event.getActor();
        if (actor instanceof NPC) {
            if (TobBossNames.MAIDEN.equals(event.getActor().getName())) {
                shutdown();
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        // TODO: Maybe check when projectile despawns? Are bloods always last constant tick?
        // Compute when a blood tile actually "activates"
        int currentCycle = client.getGameCycle();
        for (GraphicsObject graphicsObject : new ArrayList<>(maidenBloodGraphicsObjects)) {
            if (isInactive(graphicsObject)) {
                maidenBloodGraphicsObjects.remove(graphicsObject);
            } else {
                int activationCycle = graphicsObject.getStartCycle() - CYCLES_PER_GAME_TICK + BLOOD_SPAWN_ACTIVATION_CYCLE_OFFSET;
                if (currentCycle >= activationCycle) {
                    // This is now an active blood tile (technically it was on the tick before this handle invocation,
                    // so we account for that in the condition)
                    WorldPoint bloodLocation = WorldPoint.fromLocal(client, graphicsObject.getLocation());
                    log.info("" + client.getTickCount() + " - Activated blood on " + bloodLocation + "\n" +
                            "Current Cycle: " + currentCycle + " Activation Cycle: " + activationCycle + " - diff: " +
                            (currentCycle - activationCycle));

                    maidenBloodTiles.put(bloodLocation, graphicsObject);
                    maidenBloodGraphicsObjects.remove(graphicsObject);
                }
            }
        }

        // Remove "inactive" blood tiles
        for (Map.Entry<WorldPoint, GraphicsObject> bloodTileEntry : new HashSet<>(maidenBloodTiles.entrySet())) {
            if (isInactive(bloodTileEntry.getValue())) {
                maidenBloodTiles.remove(bloodTileEntry.getKey());
            }
        }
    }

    private boolean isInactive(GraphicsObject graphicsObject) {
        return graphicsObject == null || graphicsObject.finished();
    }
}
