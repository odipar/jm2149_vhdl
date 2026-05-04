package org.jm2149.vhdl;

import java.io.*;
import java.util.*;

/**
 * Minimal VCD (Value Change Dump) file parser.
 *
 * <p>Parses the header to build an identifier→signal-name map, then reads the
 * body and records every value-change event as a {@link VcdEvent}.
 *
 * <p>Only the subset of VCD used by the {@code tb_ym2149.vcd} file is
 * supported: timescale, scope/upscope, var declarations, $dumpvars, and
 * time-stamped scalar / vector changes.
 */
public final class VcdParser {

    /** A single value-change event. */
    public record VcdEvent(long timestamp, String identifier, int value) {}

    /** Ordered list of all events in the file. */
    private final List<VcdEvent> events = new ArrayList<>();

    /** identifier → signal name. */
    private final Map<String, String> signalNames = new HashMap<>();

    /** Timescale in femtoseconds (e.g. 1 for "1fs"). */
    private long timescaleFsPerUnit = 1;

    private VcdParser() {}

    /**
     * Parse a VCD file and return a {@code VcdParser} containing all events.
     *
     * @param file path to the VCD file (uncompressed)
     */
    public static VcdParser parse(File file) throws IOException {
        VcdParser p = new VcdParser();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            p.doParse(r);
        }
        return p;
    }

    /** Returns the signal name for the given VCD identifier, or the identifier itself if unknown. */
    public String signalName(String id) {
        return signalNames.getOrDefault(id, id);
    }

    /** Returns an unmodifiable view of all parsed events. */
    public List<VcdEvent> events() {
        return Collections.unmodifiableList(events);
    }

    /** Returns the timescale in femtoseconds per unit. */
    public long timescaleFsPerUnit() { return timescaleFsPerUnit; }

    // -----------------------------------------------------------------------

    private void doParse(BufferedReader r) throws IOException {
        long currentTime = 0;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;

            if (line.startsWith("$timescale")) {
                parseTimescale(r, line);
            } else if (line.startsWith("$var")) {
                parseVar(line);
            } else if (line.startsWith("#")) {
                currentTime = Long.parseLong(line.substring(1).trim());
            } else if (line.startsWith("$")) {
                // skip other VCD keywords ($scope, $upscope, $dumpvars, $end, $attrbegin, etc.)
            } else {
                // value-change line
                parseValueChange(line, currentTime);
            }
        }
    }

    private void parseTimescale(BufferedReader r, String firstLine) throws IOException {
        // Collect timescale tokens
        StringBuilder sb = new StringBuilder(firstLine.replace("$timescale", "").strip());
        while (!sb.toString().contains("$end")) {
            String line = r.readLine();
            if (line == null) break;
            sb.append(' ').append(line.strip());
        }
        String ts = sb.toString().replace("$end", "").strip().toLowerCase();
        // parse digits and unit
        int i = 0;
        while (i < ts.length() && (Character.isDigit(ts.charAt(i)) || ts.charAt(i) == ' '))
            i++;
        long number = 1;
        String numStr = ts.substring(0, i).strip();
        if (!numStr.isEmpty()) number = Long.parseLong(numStr);
        String unit = ts.substring(i).strip();
        long multiplier = switch (unit) {
            case "fs"  -> 1L;
            case "ps"  -> 1_000L;
            case "ns"  -> 1_000_000L;
            case "us"  -> 1_000_000_000L;
            case "ms"  -> 1_000_000_000_000L;
            case "s"   -> 1_000_000_000_000_000L;
            default    -> 1L;
        };
        timescaleFsPerUnit = number * multiplier;
    }

    private void parseVar(String line) {
        // $var logic 1 ! clk_i $end
        // $var logic 12 0! ch_a_o[11:0] $end
        String[] parts = line.split("\\s+");
        // parts[0]=$var, [1]=type, [2]=size, [3]=identifier, [4]=name, [5]=$end
        if (parts.length >= 5) {
            String id   = parts[3];
            String name = parts[4].replaceAll("\\[.*", ""); // strip [N:M]
            signalNames.put(id, name);
        }
    }

    private void parseValueChange(String line, long ts) {
        if (line.startsWith("b") || line.startsWith("B")) {
            // vector: "b01010101 identifier"
            int sp = line.lastIndexOf(' ');
            if (sp < 0) return;
            String valStr = line.substring(1, sp).trim();
            String id     = line.substring(sp + 1).trim();
            int val = 0;
            for (char c : valStr.toCharArray()) {
                val = (val << 1) | (c == '1' ? 1 : 0);
            }
            events.add(new VcdEvent(ts, id, val));
        } else {
            // scalar: "0identifier" or "1identifier" or "xidentifier"
            char valChar = line.charAt(0);
            String id    = line.substring(1).trim();
            int val = switch (valChar) {
                case '1' -> 1;
                case '0' -> 0;
                default  -> 0;  // 'x', 'z', etc. treated as 0
            };
            events.add(new VcdEvent(ts, id, val));
        }
    }
}
