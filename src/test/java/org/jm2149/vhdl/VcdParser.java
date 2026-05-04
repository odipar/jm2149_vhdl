package org.jm2149.vhdl;

import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;

/**
 * Streaming VCD (Value Change Dump) parser.
 *
 * <p>Parses NVC-generated VCD files (timescale 1 fs, single-character
 * identifiers) and delivers events to a {@link VcdListener} callback.
 *
 * <p>Usage:
 * <pre>
 *   try (InputStream is = new FileInputStream("trace.vcd")) {
 *       VcdParser.parse(is, listener);
 *   }
 * </pre>
 * For zipped VCD files use {@link #parseZip(InputStream, VcdListener)}.
 */
public final class VcdParser {

    private VcdParser() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Called once per VCD event.
     */
    public interface VcdListener {

        /**
         * Invoked once for every variable declaration in the VCD header.
         *
         * @param symbol   VCD identifier string (e.g. {@code "!"})
         * @param name     signal name (e.g. {@code "clk_i"})
         * @param width    bit width
         * @param scope    dotted scope path (e.g. {@code "tb_ym2149.uut"})
         */
        default void onVariable(String symbol, String name, int width, String scope) {}

        /**
         * Invoked for every value change in the VCD body.
         *
         * @param timeFs   simulation time in femtoseconds
         * @param symbol   VCD identifier string
         * @param value    new signal value (always positive; 0/1 for 1-bit)
         */
        void onChange(long timeFs, String symbol, int value);

        /**
         * Invoked once after all events have been delivered (end of file).
         * Override to flush any pending timestamp group.
         */
        default void onEnd() {}
    }

    /**
     * Parse a plain (uncompressed) VCD stream.
     */
    public static void parse(InputStream rawStream, VcdListener listener) throws IOException {
        new Parser(rawStream, listener).run();
    }

    /**
     * Parse the first entry of a ZIP archive containing a single VCD file.
     */
    public static void parseZip(InputStream zipStream, VcdListener listener) throws IOException {
        ZipInputStream zis = new ZipInputStream(zipStream);
        if (zis.getNextEntry() == null) {
            throw new IOException("Empty ZIP archive");
        }
        parse(zis, listener);
    }

    // -----------------------------------------------------------------------
    // Internal parser
    // -----------------------------------------------------------------------

    private static final class Parser {

        private final BufferedReader reader;
        private final VcdListener   listener;

        // Current simulation time
        private long currentTime = 0;

        // Scope stack for building full dotted names
        private final Deque<String> scopeStack = new ArrayDeque<>();

        Parser(InputStream in, VcdListener listener) {
            this.reader   = new BufferedReader(new InputStreamReader(in));
            this.listener = listener;
        }

        void run() throws IOException {
            String line;
            boolean inDumpvars = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("$timescale")) {
                    // skip multi-line $timescale
                    if (!line.contains("$end")) consumeUntilEnd();
                    continue;
                }

                if (line.startsWith("$date") || line.startsWith("$version")
                        || line.startsWith("$comment") || line.startsWith("$attrbegin")) {
                    if (!line.contains("$end")) consumeUntilEnd();
                    continue;
                }

                if (line.startsWith("$attrend")) continue;

                if (line.startsWith("$scope")) {
                    // $scope vhdl_architecture name $end  or  $scope module name $end
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        String scopeName = parts[2].equals("$end") ? parts[1] : parts[2];
                        scopeStack.push(scopeName);
                    }
                    if (!line.contains("$end")) consumeUntilEnd();
                    continue;
                }

                if (line.startsWith("$upscope")) {
                    if (!scopeStack.isEmpty()) scopeStack.pop();
                    if (!line.contains("$end")) consumeUntilEnd();
                    continue;
                }

                if (line.startsWith("$var")) {
                    // $var logic <width> <symbol> <name>[...] $end
                    // May span multiple tokens on the same line
                    String full = line;
                    if (!full.contains("$end")) {
                        full += " " + consumeUntilEnd();
                    }
                    parseVar(full);
                    continue;
                }

                if (line.startsWith("$dumpvars")) {
                    inDumpvars = true;
                    // initial values follow on subsequent lines
                    String rest = line.substring("$dumpvars".length()).trim();
                    if (!rest.isEmpty()) processChangeLine(rest, inDumpvars);
                    continue;
                }

                if (line.equals("$end")) {
                    inDumpvars = false;
                    continue;
                }

                if (line.startsWith("#")) {
                    // Timestamp
                    currentTime = Long.parseLong(line.substring(1).trim());
                    continue;
                }

                // Value change
                if (inDumpvars || line.startsWith("b") || line.startsWith("B")
                        || line.startsWith("0") || line.startsWith("1")
                        || line.startsWith("x") || line.startsWith("X")
                        || line.startsWith("z") || line.startsWith("Z")) {
                    processChangeLine(line, inDumpvars);
                }
            }
            listener.onEnd();
        }

        // Build current scope path
        private String currentScope() {
            if (scopeStack.isEmpty()) return "";
            // scopeStack is LIFO, so traverse in reverse
            StringBuilder sb = new StringBuilder();
            String[] arr = scopeStack.toArray(new String[0]);
            for (int i = arr.length - 1; i >= 0; i--) {
                if (sb.length() > 0) sb.append('.');
                sb.append(arr[i]);
            }
            return sb.toString();
        }

        private void parseVar(String line) {
            // $var logic 1 ! clk_i $end
            // $var logic 12 ) ch_a_o[11:0] $end
            String[] tokens = line.trim().split("\\s+");
            // tokens[0]=$var tokens[1]=type tokens[2]=width tokens[3]=symbol tokens[4]=name ...
            if (tokens.length < 5) return;
            int    width  = Integer.parseInt(tokens[2]);
            String symbol = tokens[3];
            // Name may have a bit-range suffix like [11:0]; strip it.
            String name = tokens[4].replaceAll("\\[.*\\]", "");
            if (name.isEmpty()) name = tokens[4]; // keep original if nothing left after stripping
            String scope = currentScope();
            listener.onVariable(symbol, name, width, scope);
        }

        private void processChangeLine(String line, boolean inDumpvars) {
            if (line.startsWith("b") || line.startsWith("B")) {
                // b<binary> <symbol>
                int sp = line.indexOf(' ');
                if (sp < 0) return;
                String binStr = line.substring(1, sp);
                String symbol = line.substring(sp + 1).trim();
                int value = parseBin(binStr);
                listener.onChange(currentTime, symbol, value);
            } else if (line.length() >= 2) {
                // <0|1|x|z><symbol>
                char v   = line.charAt(0);
                String symbol = line.substring(1).trim();
                int value = (v == '1') ? 1 : 0;
                listener.onChange(currentTime, symbol, value);
            }
        }

        /**
         * Consume tokens until {@code $end} is seen, returning all consumed text.
         */
        private String consumeUntilEnd() throws IOException {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.contains("$end")) {
                    sb.append(line);
                    break;
                }
                sb.append(line).append(' ');
            }
            return sb.toString();
        }

        private static int parseBin(String s) {
            int result = 0;
            for (int i = 0; i < s.length(); i++) {
                result <<= 1;
                if (s.charAt(i) == '1') result |= 1;
                // x / z treated as 0
            }
            return result;
        }
    }
}
