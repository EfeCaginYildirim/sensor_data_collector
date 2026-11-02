package io.github.efecaginyildirim.collector.fakeapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.efecaginyildirim.collector.model.ComputedFeature;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Deterministic synthetic data for a fleet of electric motors, in the vendor's wire format.
 *
 * <p>Every value is a pure function of {@code (seed, monitorId, timestamp)}: asking for the same
 * sample twice — in one large request or split across many small ones — returns identical data.
 * That property is what makes chunked, parallel collection testable.
 *
 * <p>The signal model is deliberately simple but physically plausible:
 * <ul>
 *   <li>every motor has a baseline RMS velocity, a nominal speed and a daily load cycle;</li>
 *   <li>every third motor develops an outer-race bearing defect: after an onset date its
 *       {@code wear} grows linearly from 0 to 1, raising kurtosis, crest factor, envelope
 *       values, the BPFO amplitude and temperature, and lowering the health score;</li>
 *   <li>Gaussian noise, occasional missing values and (optionally) duplicated samples make the
 *       data realistically messy.</li>
 * </ul>
 */
public final class SyntheticFleet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Generator options.
     *
     * @param seed             random seed; same seed, same fleet
     * @param referenceTime    "today" of the scenario; degradation onsets are placed before it
     * @param computedInterval spacing of computed-feature samples, aligned to the epoch
     * @param missingRate      probability that an individual computed value is reported as {@code null}
     * @param duplicateRate    probability that a computed sample is emitted twice
     */
    public record Options(long seed, Instant referenceTime, Duration computedInterval,
                          double missingRate, double duplicateRate) {
        public Options {
            Objects.requireNonNull(referenceTime, "referenceTime");
            if (computedInterval.isNegative() || computedInterval.isZero()) {
                throw new IllegalArgumentException("computedInterval must be positive");
            }
        }

        /**
         * Defaults: 6-hour computed samples, 2 % missing values, no duplicates.
         *
         * @param seed          random seed
         * @param referenceTime scenario "today"
         * @return options
         */
        public static Options defaults(long seed, Instant referenceTime) {
            return new Options(seed, referenceTime, Duration.ofHours(6), 0.02, 0.0);
        }
    }

    /**
     * Fixed characteristics of one simulated motor.
     *
     * @param monitorId        vendor monitor id
     * @param baselineVelocity healthy RMS velocity in mm/s
     * @param nominalRpm       nameplate speed
     * @param degradationOnset when bearing wear starts, or {@code null} for a healthy motor
     * @param wearPerDay       wear increase per day after the onset
     */
    public record MotorProfile(int monitorId, double baselineVelocity, double nominalRpm,
                               Instant degradationOnset, double wearPerDay) {

        /**
         * Bearing wear at a given instant.
         *
         * @param t instant
         * @return wear in {@code [0, 1]}
         */
        public double wear(Instant t) {
            if (degradationOnset == null || t.isBefore(degradationOnset)) {
                return 0.0;
            }
            double days = Duration.between(degradationOnset, t).toSeconds() / 86_400.0;
            return Math.min(1.0, days * wearPerDay);
        }
    }

    private final int plantId;
    private final Options options;
    private final Map<Integer, MotorProfile> profiles;

    private SyntheticFleet(int plantId, Options options, Map<Integer, MotorProfile> profiles) {
        this.plantId = plantId;
        this.options = options;
        this.profiles = Collections.unmodifiableMap(profiles);
    }

    /**
     * Creates a fleet.
     *
     * @param plantId    plant id served by {@code /plants/{id}}
     * @param monitorIds monitors in the fleet; every third one (starting with the first) degrades
     * @param options    generator options
     * @return the fleet
     */
    public static SyntheticFleet create(int plantId, List<Integer> monitorIds, Options options) {
        Map<Integer, MotorProfile> profiles = new LinkedHashMap<>();
        for (int i = 0; i < monitorIds.size(); i++) {
            int id = monitorIds.get(i);
            SplittableRandom r = new SplittableRandom(mix(options.seed(), id, 0, 0));
            double baseline = 0.8 + r.nextDouble() * 1.7;
            double rpm = r.nextBoolean() ? 1485.0 : 2970.0;
            Instant onset = null;
            double rate = 0.0;
            if (i % 3 == 0) {
                onset = options.referenceTime().minus(Duration.ofDays(30 + r.nextInt(90)));
                rate = 0.006 + r.nextDouble() * 0.008;
            }
            profiles.put(id, new MotorProfile(id, baseline, rpm, onset, rate));
        }
        return new SyntheticFleet(plantId, options, profiles);
    }

    /** @return the plant id */
    public int plantId() {
        return plantId;
    }

    /** @return monitor ids in creation order */
    public List<Integer> monitorIds() {
        return List.copyOf(profiles.keySet());
    }

    /**
     * @param monitorId monitor id
     * @return whether the fleet contains the monitor
     */
    public boolean hasMonitor(int monitorId) {
        return profiles.containsKey(monitorId);
    }

    /**
     * @param monitorId monitor id
     * @return the motor profile
     * @throws IllegalArgumentException for unknown monitors
     */
    public MotorProfile profile(int monitorId) {
        MotorProfile p = profiles.get(monitorId);
        if (p == null) {
            throw new IllegalArgumentException("unknown monitor " + monitorId);
        }
        return p;
    }

    /** @return the generator options */
    public Options options() {
        return options;
    }

    // ─── Wire format ─────────────────────────────────────────────────────────────

    /**
     * The plant hierarchy with each monitor's status as of {@code asOf}. Monitors are grouped
     * two per machine and three machines per machine group, in one area.
     *
     * @param asOf instant of the status snapshot
     * @return the {@code data} object of {@code GET /plants/{id}}
     */
    public ObjectNode plantJson(Instant asOf) {
        ObjectNode plant = MAPPER.createObjectNode();
        plant.put("id", plantId);
        plant.put("name", "Demo Plant");
        plant.put("organizationName", "Demo Industries");
        plant.put("odrEnabled", false);
        ObjectNode area = plant.putArray("areas").addObject();
        area.put("id", plantId * 100 + 1);
        area.put("name", "Hall A");
        area.put("status", 1);
        ArrayNode groups = area.putArray("machineGroups");

        List<Integer> ids = monitorIds();
        ArrayNode machines = null;
        ArrayNode monitors = null;
        for (int i = 0; i < ids.size(); i++) {
            int machineIndex = i / 2;
            if (i % 6 == 0) {
                ObjectNode group = groups.addObject();
                group.put("id", plantId * 1000 + i / 6 + 1);
                group.put("name", "Line " + (i / 6 + 1));
                group.put("status", 1);
                machines = group.putArray("machines");
            }
            if (i % 2 == 0) {
                ObjectNode machine = Objects.requireNonNull(machines).addObject();
                machine.put("id", plantId * 10_000 + machineIndex + 1);
                machine.put("name", String.format("Motor M-%02d", machineIndex + 1));
                machine.put("monitorCount", Math.min(2, ids.size() - i));
                monitors = machine.putArray("monitors");
            }
            Objects.requireNonNull(monitors).add(monitorJson(profile(ids.get(i)), i % 2 == 0 ? "DE" : "NDE", asOf));
        }
        return plant;
    }

    private ObjectNode monitorJson(MotorProfile p, String side, Instant asOf) {
        double wear = p.wear(asOf);
        Map<ComputedFeature, Double> now = computedValues(p, asOf, new SplittableRandom(mix(options.seed(), p.monitorId(), asOf.getEpochSecond(), 1)));
        ObjectNode m = MAPPER.createObjectNode();
        m.put("id", p.monitorId());
        m.put("name", "Bearing " + side);
        m.put("deviceIdentifier", String.format("SN-%06d", p.monitorId()));
        m.put("orientationCode", side.equals("DE") ? "H" : "V");
        m.put("subscriptionStart", "2024-01-01");
        m.put("subscriptionEnd", "2027-01-01");
        int status = wear > 0.6 ? 3 : wear > 0.3 ? 2 : 1;
        m.put("alarms", status == 3 ? 1 : 0);
        m.put("healthScore", (int) Math.round(100 - 80 * wear));
        m.put("status", status);
        m.put("trend", wear > 0 ? -1 : 0);
        m.put("speed", round(now.get(ComputedFeature.RPM)));
        putReading(m, "velocityX", now.get(ComputedFeature.VRMSX), 2.8, 4.5, 7.1);
        putReading(m, "velocityY", now.get(ComputedFeature.VRMSY), 2.8, 4.5, 7.1);
        putReading(m, "velocityZ", now.get(ComputedFeature.VRMSZ), 2.8, 4.5, 7.1);
        putReading(m, "temperature", now.get(ComputedFeature.TA), 60, 70, 80);
        return m;
    }

    private static void putReading(ObjectNode m, String name, double value, double op, double caution, double warning) {
        m.put(name, round(value));
        m.put(name + "OperationalThreshold", op);
        m.put(name + "CautionThreshold", caution);
        m.put(name + "WarningThreshold", warning);
    }

    /**
     * Daily basic-feature aggregates at 00:00 UTC with {@code from <= time <= to}
     * (both bounds inclusive, see API_CONTRACT.md).
     *
     * @param monitorId monitor id
     * @param from      inclusive start
     * @param to        inclusive end
     * @return wire-format objects in chronological order
     */
    public List<ObjectNode> basicFeatures(int monitorId, Instant from, Instant to) {
        MotorProfile p = profile(monitorId);
        List<ObjectNode> out = new ArrayList<>();
        Instant day = from.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
        if (day.isBefore(from)) {
            day = day.plus(Duration.ofDays(1));
        }
        for (; !day.isAfter(to); day = day.plus(Duration.ofDays(1))) {
            SplittableRandom r = new SplittableRandom(mix(options.seed(), monitorId, day.getEpochSecond(), 2));
            Map<ComputedFeature, Double> v = computedValues(p, day.plus(Duration.ofHours(12)), r);
            ObjectNode avg = MAPPER.createObjectNode();
            avg.put("velocityX", round(v.get(ComputedFeature.VRMSX)));
            avg.put("velocityY", round(v.get(ComputedFeature.VRMSY)));
            avg.put("velocityZ", round(v.get(ComputedFeature.VRMSZ)));
            avg.put("temperature", round(v.get(ComputedFeature.TA)));

            ObjectNode bf = MAPPER.createObjectNode();
            bf.put("id", "bf-" + monitorId + "-" + day.getEpochSecond() / 86_400);
            bf.put("time", day.toString().replace("Z", ".000Z"));
            bf.put("monitorId", monitorId);
            bf.put("jsonAvg", avg.toString());
            bf.put("jsonMin", scaled(avg, 0.8).toString());
            bf.put("jsonMax", scaled(avg, 1.25).toString());
            out.add(bf);
        }
        return out;
    }

    /**
     * Computed-feature samples on the configured grid with {@code from <= timestamp <= to}.
     *
     * @param monitorId monitor id
     * @param from      inclusive start
     * @param to        inclusive end
     * @return wire-format objects in chronological order (possibly containing duplicates)
     */
    public List<ObjectNode> computedFeatures(int monitorId, Instant from, Instant to) {
        MotorProfile p = profile(monitorId);
        long step = options.computedInterval().toSeconds();
        long first = Math.floorDiv(from.getEpochSecond() + step - 1, step) * step;
        List<ObjectNode> out = new ArrayList<>();
        for (long s = first; s <= to.getEpochSecond(); s += step) {
            Instant t = Instant.ofEpochSecond(s);
            SplittableRandom r = new SplittableRandom(mix(options.seed(), monitorId, s, 3));
            Map<ComputedFeature, Double> v = computedValues(p, t, r);
            ObjectNode sample = MAPPER.createObjectNode();
            sample.put("timestamp", t.toString().replace("Z", ".000Z"));
            for (ComputedFeature f : ComputedFeature.values()) {
                if (r.nextDouble() < options.missingRate()) {
                    sample.putNull(f.apiName());
                } else {
                    sample.put(f.apiName(), round(v.get(f)));
                }
            }
            out.add(sample);
            if (r.nextDouble() < options.duplicateRate()) {
                out.add(sample.deepCopy());
            }
        }
        return out;
    }

    // ─── Signal model ────────────────────────────────────────────────────────────

    /**
     * Noise-free-plus-noise feature values of a motor at an instant.
     *
     * @param p motor profile
     * @param t instant
     * @param r random source dedicated to this (monitor, instant)
     * @return all 29 features
     */
    static Map<ComputedFeature, Double> computedValues(MotorProfile p, Instant t, SplittableRandom r) {
        double wear = p.wear(t);
        double hourAngle = 2 * Math.PI * (t.getEpochSecond() % 86_400) / 86_400.0;
        double dayAngle = 2 * Math.PI * (Math.floorMod(t.getEpochSecond() / 86_400, 7)) / 7.0;
        double load = clamp(65 + 15 * Math.sin(dayAngle) + 8 * Math.sin(hourAngle) + gauss(r, 3), 0, 100);
        double idle = clamp(0.05 + (load < 40 ? 0.3 : 0.0) + gauss(r, 0.02), 0, 1);
        double rpm = p.nominalRpm() * (1 - 0.012 * load / 100) + gauss(r, 1.5);

        double velocity = p.baselineVelocity() * (1 + 2.5 * wear * wear + 0.6 * wear) * (0.9 + 0.2 * load / 100);
        double vx = velocity * 1.00 + gauss(r, 0.03 * p.baselineVelocity());
        double vy = velocity * 0.85 + gauss(r, 0.03 * p.baselineVelocity());
        double vz = velocity * 0.60 + gauss(r, 0.03 * p.baselineVelocity());

        double kurt = 3.0 + 6.0 * wear * wear;
        double crest = 3.0 + 2.5 * wear;
        double grmsBase = velocity * rpm / 60.0 * 2 * Math.PI / 9806.65 * 4;

        Map<ComputedFeature, Double> v = new EnumMap<>(ComputedFeature.class);
        double[] axis = {1.0, 0.85, 0.60};
        ComputedFeature[][] perAxis = {
            {ComputedFeature.KURTACCLX, ComputedFeature.KURTACCLY, ComputedFeature.KURTACCLZ},
            {ComputedFeature.CRESTACCLX, ComputedFeature.CRESTACCLY, ComputedFeature.CRESTACCLZ},
            {ComputedFeature.GRMSACCLX, ComputedFeature.GRMSACCLY, ComputedFeature.GRMSACCLZ},
            {ComputedFeature.PPACCLX, ComputedFeature.PPACCLY, ComputedFeature.PPACCLZ},
            {ComputedFeature.SWRMSX, ComputedFeature.SWRMSY, ComputedFeature.SWRMSZ},
            {ComputedFeature.PPSWX, ComputedFeature.PPSWY, ComputedFeature.PPSWZ},
        };
        for (int a = 0; a < 3; a++) {
            double k = Math.max(1.5, kurt + gauss(r, 0.15));
            double c = Math.max(1.2, crest + gauss(r, 0.1));
            double g = Math.max(0.001, grmsBase * axis[a] * (1 + 0.8 * wear) + gauss(r, 0.01));
            double sw = Math.max(0.0, 0.05 * axis[a] + 1.2 * wear * axis[a] + gauss(r, 0.01));
            v.put(perAxis[0][a], k);
            v.put(perAxis[1][a], c);
            v.put(perAxis[2][a], g);
            v.put(perAxis[3][a], 2 * c * g);
            v.put(perAxis[4][a], sw);
            v.put(perAxis[5][a], 2.8 * sw + Math.abs(gauss(r, 0.02)));
        }
        v.put(ComputedFeature.IDLE, idle);
        v.put(ComputedFeature.RPM, rpm);
        v.put(ComputedFeature.BPFI, Math.max(0, 0.010 + 0.08 * wear + gauss(r, 0.003)));
        v.put(ComputedFeature.BPFO, Math.max(0, 0.020 + 0.60 * wear + gauss(r, 0.005)));
        v.put(ComputedFeature.BSF, Math.max(0, 0.008 + 0.05 * wear + gauss(r, 0.002)));
        v.put(ComputedFeature.FTF, Math.max(0, 0.005 + 0.02 * wear + gauss(r, 0.001)));
        v.put(ComputedFeature.LOAD, load);
        v.put(ComputedFeature.VRMSX, Math.max(0.01, vx));
        v.put(ComputedFeature.VRMSY, Math.max(0.01, vy));
        v.put(ComputedFeature.VRMSZ, Math.max(0.01, vz));
        v.put(ComputedFeature.TA, 35 + 0.12 * load + 12 * wear + 3 * Math.sin(hourAngle) + gauss(r, 0.4));
        return v;
    }

    private static ObjectNode scaled(ObjectNode avg, double factor) {
        ObjectNode out = MAPPER.createObjectNode();
        avg.properties().forEach(e -> out.put(e.getKey(), round(e.getValue().asDouble() * factor)));
        return out;
    }

    private static double gauss(SplittableRandom r, double sigma) {
        double u1 = 1.0 - r.nextDouble();
        double u2 = r.nextDouble();
        return sigma * Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** SplitMix-style hash so every (seed, monitor, time, stream) gets an independent random sequence. */
    static long mix(long seed, long monitorId, long epochSecond, long stream) {
        long h = seed;
        h = h * 0x9E3779B97F4A7C15L + monitorId;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L + epochSecond;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL + stream;
        return h ^ (h >>> 32);
    }
}
