package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The only file in the app that touches BRouter classes directly.
 *
 * It is deliberately Java and deliberately in BRouter's own package: VoiceHintList.list and the
 * VoiceHint fields are package-private, and Kotlin has no way to reach them. Everything is
 * copied out into primitive arrays so the Kotlin side never holds a BRouter object.
 */
public final class BRouterBridge {

    private BRouterBridge() {
    }

    /** Plain data out. All arrays are non-null on success; {@link #error} is non-null on failure. */
    public static final class Result {
        public double[] lats;
        public double[] lons;
        public int distanceM;
        public int seconds;
        /** Parallel arrays, one entry per voice hint. */
        public int[] hintIndex;
        public int[] hintCmd;
        public double[] hintDistToNext;
        public int[] hintExit;
        public String error;
    }

    /** Osmand-style hints: full left/right/slight/sharp/keep/roundabout vocabulary. */
    private static final int TURN_INSTRUCTION_MODE = 3;

    /**
     * @param segmentDir directory holding *.rd5 files
     * @param profile    path to a *.brf profile; lookups.dat must sit in the same directory
     * @param maxMs      hard wall-clock limit for the search
     * @param memoryMb   node-cache budget handed to BRouter (its "memoryclass")
     */
    public static Result route(File segmentDir, File profile,
                               double fromLat, double fromLon, double toLat, double toLon,
                               long maxMs, int memoryMb) {
        Result out = new Result();

        RoutingContext rc = new RoutingContext();
        rc.localFunction = profile.getAbsolutePath();
        rc.memoryclass = memoryMb;
        rc.turnInstructionMode = TURN_INSTRUCTION_MODE;

        List<OsmNodeNamed> waypoints = new ArrayList<>(2);
        waypoints.add(node("from", fromLat, fromLon));
        waypoints.add(node("to", toLat, toLon));

        // The constructor parses the profile and can throw (bad profile, missing lookups.dat),
        // so it sits inside the same guard as the search itself.
        RoutingEngine engine;
        try {
            engine = new RoutingEngine(null, null, segmentDir, waypoints, rc,
                    RoutingEngine.BROUTER_ENGINEMODE_ROUTING);
            engine.quite = true;
            engine.doRun(maxMs);
        } catch (Throwable t) {
            out.error = t.getMessage() != null ? t.getMessage() : t.toString();
            return out;
        }

        if (engine.getErrorMessage() != null) {
            out.error = engine.getErrorMessage();
            return out;
        }
        OsmTrack track = engine.getFoundTrack();
        if (track == null || track.nodes == null || track.nodes.isEmpty()) {
            out.error = "No route found";
            return out;
        }

        int n = track.nodes.size();
        out.lats = new double[n];
        out.lons = new double[n];
        for (int i = 0; i < n; i++) {
            OsmPathElement e = track.nodes.get(i);
            out.lats[i] = toLat(e.getILat());
            out.lons[i] = toLon(e.getILon());
        }
        out.distanceM = track.distance;
        out.seconds = track.getTotalSeconds();

        List<VoiceHint> hints = track.voiceHints != null ? track.voiceHints.list : null;
        int h = hints != null ? hints.size() : 0;
        out.hintIndex = new int[h];
        out.hintCmd = new int[h];
        out.hintDistToNext = new double[h];
        out.hintExit = new int[h];
        for (int i = 0; i < h; i++) {
            VoiceHint vh = hints.get(i);
            out.hintIndex[i] = vh.indexInTrack;
            out.hintCmd[i] = vh.cmd;
            out.hintDistToNext[i] = vh.distanceToNext;
            out.hintExit[i] = (vh.cmd == VoiceHint.RNDB || vh.cmd == VoiceHint.RNLB) ? vh.getExitNumber() : 0;
        }
        return out;
    }

    // BRouter stores coordinates as micro-degrees offset to be non-negative.
    private static OsmNodeNamed node(String name, double lat, double lon) {
        OsmNodeNamed n = new OsmNodeNamed();
        n.name = name;
        n.ilon = (int) ((lon + 180.0) * 1_000_000.0 + 0.5);
        n.ilat = (int) ((lat + 90.0) * 1_000_000.0 + 0.5);
        return n;
    }

    private static double toLat(int ilat) {
        return (ilat - 90_000_000) / 1_000_000.0;
    }

    private static double toLon(int ilon) {
        return (ilon - 180_000_000) / 1_000_000.0;
    }
}
