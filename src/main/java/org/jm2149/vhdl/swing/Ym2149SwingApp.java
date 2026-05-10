package org.jm2149.vhdl.swing;

import org.jm2149.vhdl.indexed.Ym2149AudioIndexed;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Swing demonstration application for {@link Ym2149AudioIndexed}.
 *
 * <p>The PSG model is clocked at 250 kHz ({@code en_clk_psg_i = true},
 * {@code sel_n_i = false}).  The three channel outputs are linearly summed and
 * resampled to 44.1 kHz for real-time playback through the default system audio
 * device.</p>
 *
 * <h2>Controls</h2>
 * <ul>
 *   <li>Channels A / B / C — tone period, volume, tone/noise/envelope enable</li>
 *   <li>Noise generator — period</li>
 *   <li>Envelope generator — period and shape (16 standard YM2149 shapes)</li>
 *   <li>Play / Stop button to start and stop audio output</li>
 * </ul>
 */
public final class Ym2149SwingApp extends JFrame {

    // -----------------------------------------------------------------------
    // Clock / audio constants
    // -----------------------------------------------------------------------

    /** PSG master clock rate (cycles per second fed to risingEdge). */
    private static final int PSG_CLOCK_HZ  = 250_000;

    /** Target audio output sample rate (Hz). */
    private static final int AUDIO_RATE_HZ = 44_100;

    /**
     * Number of audio samples per write to the {@link SourceDataLine}.
     * Larger values increase latency; smaller values increase CPU overhead.
     */
    private static final int AUDIO_BUF_SAMPS = 1024;

    /**
     * Maximum possible linear sum of all three channels
     * (each channel's 12-bit DACROM output is at most 0xFFF = 4095).
     */
    private static final int MAX_DAC_SUM = 3 * 0xFFF;

    // -----------------------------------------------------------------------
    // Immutable configuration snapshot — published atomically via volatile
    // -----------------------------------------------------------------------

    /**
     * Captures a consistent view of all PSG parameters set through the UI.
     * Written on the EDT; read once per audio buffer on the audio thread.
     */
    private record PsgConfig(
            int     toneA,     int toneB,    int toneC,
            int     volA,      int volB,     int volC,
            boolean envModeA,  boolean envModeB,  boolean envModeC,
            boolean toneEnA,   boolean toneEnB,   boolean toneEnC,
            boolean noiseEnA,  boolean noiseEnB,  boolean noiseEnC,
            int noisePeriod,
            int envPeriod,
            int envShape
    ) {}

    /** Current PSG configuration; volatile guarantees visibility across threads. */
    private volatile PsgConfig config = new PsgConfig(
            500,  500,  500,
            12,   12,   12,
            false, false, false,
            true,  false, false,
            false, false, false,
            16, 1000, 8
    );

    // -----------------------------------------------------------------------
    // PSG model — only accessed by the audio thread after startAudio()
    // -----------------------------------------------------------------------

    private final Ym2149AudioIndexed psg = new Ym2149AudioIndexed();

    // -----------------------------------------------------------------------
    // Audio thread state
    // -----------------------------------------------------------------------

    private volatile boolean running = false;
    private Thread           audioThread;
    private SourceDataLine   line;

    // -----------------------------------------------------------------------
    // UI control references (needed for reading values in refreshConfig)
    // -----------------------------------------------------------------------

    private final JSlider[]         slTone      = new JSlider[3];
    private final JSlider[]         slVol       = new JSlider[3];
    private final JCheckBox[]       cbToneEn    = new JCheckBox[3];
    private final JCheckBox[]       cbNoiseEn   = new JCheckBox[3];
    private final JCheckBox[]       cbEnvMode   = new JCheckBox[3];
    private       JSlider           slNoise;
    private       JSlider           slEnvPeriod;
    private       JComboBox<String> cmbEnvShape;
    private       JButton           btnPlay;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /** Launch the application on the Swing Event Dispatch Thread. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Ym2149SwingApp().setVisible(true));
    }

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /** Construct and lay out the main window. */
    public Ym2149SwingApp() {
        super("YM2149 PSG Tester");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopAudio();
                dispose();
                System.exit(0);
            }
        });

        buildUi();
        pack();
        setResizable(false);
        setLocationRelativeTo(null);

        psg.applyReset();
    }

    // -----------------------------------------------------------------------
    // UI construction
    // -----------------------------------------------------------------------

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Three channel panels stacked vertically on the left
        JPanel channelsPanel = new JPanel(new GridLayout(3, 1, 4, 4));
        String[] chNames = {"Channel A", "Channel B", "Channel C"};
        for (int ch = 0; ch < 3; ch++) {
            channelsPanel.add(buildChannelPanel(ch, chNames[ch]));
        }
        root.add(channelsPanel, BorderLayout.CENTER);

        // Noise / Envelope / Play-Stop on the right side
        JPanel sidePanel = new JPanel(new GridLayout(3, 1, 4, 4));
        sidePanel.add(buildNoisePanel());
        sidePanel.add(buildEnvelopePanel());
        sidePanel.add(buildControlPanel());
        root.add(sidePanel, BorderLayout.EAST);

        setContentPane(root);
    }

    private JPanel buildChannelPanel(int ch, String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);

        // Row 0: Tone period slider
        g.gridx = 0; g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.fill   = GridBagConstraints.NONE;
        g.weightx = 0;
        p.add(new JLabel("Tone:"), g);

        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        JSlider sl = new JSlider(6, 4095, config.toneA());
        sl.setPreferredSize(new Dimension(220, sl.getPreferredSize().height));
        slTone[ch] = sl;
        p.add(sl, g);

        g.gridx = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        JLabel lblPeriod = new JLabel(String.format("%4d", sl.getValue()));
        p.add(lblPeriod, g);

        g.gridx = 3;
        JLabel lblFreq = new JLabel(toneFreqLabel(sl.getValue()));
        lblFreq.setPreferredSize(new Dimension(80, lblFreq.getPreferredSize().height));
        p.add(lblFreq, g);

        sl.addChangeListener(e -> {
            lblPeriod.setText(String.format("%4d", sl.getValue()));
            lblFreq.setText(toneFreqLabel(sl.getValue()));
            refreshConfig();
        });

        // Row 1: Volume slider
        g.gridx = 0; g.gridy = 1;
        g.fill = GridBagConstraints.NONE; g.weightx = 0;
        p.add(new JLabel("Volume:"), g);

        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        JSlider volSl = new JSlider(0, 15, 12);
        volSl.setMajorTickSpacing(5);
        volSl.setMinorTickSpacing(1);
        volSl.setPaintTicks(true);
        slVol[ch] = volSl;
        p.add(volSl, g);

        g.gridx = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        JLabel lblVol = new JLabel(String.format("%2d", volSl.getValue()));
        p.add(lblVol, g);

        volSl.addChangeListener(e -> {
            lblVol.setText(String.format("%2d", volSl.getValue()));
            refreshConfig();
        });

        // Row 2: Enable checkboxes
        g.gridx = 0; g.gridy = 2; g.gridwidth = 1;
        cbToneEn[ch]  = new JCheckBox("Tone",     ch == 0);
        cbNoiseEn[ch] = new JCheckBox("Noise",    false);
        cbEnvMode[ch] = new JCheckBox("Envelope", false);

        ChangeListener cbRefresh = e -> refreshConfig();
        cbToneEn[ch].addChangeListener(cbRefresh);
        cbNoiseEn[ch].addChangeListener(cbRefresh);
        cbEnvMode[ch].addChangeListener(cbRefresh);

        p.add(cbToneEn[ch],  g); g.gridx = 1;
        p.add(cbNoiseEn[ch], g); g.gridx = 2;
        p.add(cbEnvMode[ch], g);

        return p;
    }

    private JPanel buildNoisePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Noise Generator"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);

        g.gridx = 0; g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        p.add(new JLabel("Period:"), g);

        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        slNoise = new JSlider(1, 31, config.noisePeriod());
        p.add(slNoise, g);

        g.gridx = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        JLabel lbl = new JLabel(String.valueOf(slNoise.getValue()));
        lbl.setPreferredSize(new Dimension(24, lbl.getPreferredSize().height));
        p.add(lbl, g);

        slNoise.addChangeListener(e -> {
            lbl.setText(String.valueOf(slNoise.getValue()));
            refreshConfig();
        });

        return p;
    }

    // Standard YM2149 envelope shape names (index = register 13 value, bits CONT/ATT/ALT/HOLD)
    private static final String[] ENV_SHAPE_NAMES = {
        " 0 (\\____)",    " 1 (\\____)",   " 2 (\\____)",   " 3 (\\_____)",
        " 4 (/|___)",     " 5 (/|___)",    " 6 (/|___)",    " 7 (/|____)",
        " 8 (\\\\\\\\)",  " 9 (\\_____)",  "10 (\\/\\/)",   "11 (\\¯¯¯¯)",
        "12 (////)",      "13 (/¯¯¯¯)",    "14 (/\\/\\)",   "15 (/|____)"
    };

    private JPanel buildEnvelopePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Envelope Generator"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);

        // Period row
        g.gridx = 0; g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        p.add(new JLabel("Period:"), g);

        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        slEnvPeriod = new JSlider(1, 65535, config.envPeriod());
        p.add(slEnvPeriod, g);

        g.gridx = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        JLabel lblEP = new JLabel(String.valueOf(slEnvPeriod.getValue()));
        lblEP.setPreferredSize(new Dimension(40, lblEP.getPreferredSize().height));
        p.add(lblEP, g);

        slEnvPeriod.addChangeListener(e -> {
            lblEP.setText(String.valueOf(slEnvPeriod.getValue()));
            refreshConfig();
        });

        // Shape row
        g.gridx = 0; g.gridy = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        p.add(new JLabel("Shape:"), g);

        g.gridx = 1; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL;
        cmbEnvShape = new JComboBox<>(ENV_SHAPE_NAMES);
        cmbEnvShape.setSelectedIndex(config.envShape());
        cmbEnvShape.addActionListener(e -> refreshConfig());
        p.add(cmbEnvShape, g);

        return p;
    }

    private JPanel buildControlPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));

        btnPlay = new JButton("▶  Play");
        btnPlay.setFont(btnPlay.getFont().deriveFont(Font.BOLD, 14f));
        btnPlay.setPreferredSize(new Dimension(110, 36));
        btnPlay.addActionListener(e -> togglePlayStop());
        p.add(btnPlay);

        return p;
    }

    // -----------------------------------------------------------------------
    // Configuration snapshot — called on the EDT whenever any control changes
    // -----------------------------------------------------------------------

    /**
     * Publish a new {@link PsgConfig} snapshot from the current UI state.
     * Safe to call from the EDT; the audio thread reads the snapshot atomically.
     */
    private void refreshConfig() {
        config = new PsgConfig(
                slTone[0].getValue(),   slTone[1].getValue(),   slTone[2].getValue(),
                slVol[0].getValue(),    slVol[1].getValue(),    slVol[2].getValue(),
                cbEnvMode[0].isSelected(), cbEnvMode[1].isSelected(), cbEnvMode[2].isSelected(),
                cbToneEn[0].isSelected(),  cbToneEn[1].isSelected(),  cbToneEn[2].isSelected(),
                cbNoiseEn[0].isSelected(), cbNoiseEn[1].isSelected(), cbNoiseEn[2].isSelected(),
                slNoise.getValue(),
                slEnvPeriod.getValue(),
                cmbEnvShape.getSelectedIndex()
        );
    }

    // -----------------------------------------------------------------------
    // Play / Stop
    // -----------------------------------------------------------------------

    private void togglePlayStop() {
        if (running) {
            stopAudio();
            btnPlay.setText("▶  Play");
        } else {
            try {
                startAudio();
                btnPlay.setText("⏹  Stop");
            } catch (LineUnavailableException ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Cannot open audio output:\n" + ex.getMessage(),
                        "Audio Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void startAudio() throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(AUDIO_RATE_HZ, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        line = (SourceDataLine) AudioSystem.getLine(info);
        // Native buffer: 4 × AUDIO_BUF_SAMPS × 2 bytes (16-bit), giving ~2 write-ahead
        line.open(fmt, AUDIO_BUF_SAMPS * 8);
        line.start();

        psg.applyReset();
        running = true;
        audioThread = new Thread(this::audioLoop, "ym2149-audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private void stopAudio() {
        running = false;
        if (audioThread != null) {
            try {
                audioThread.join(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
            line = null;
        }
    }

    // -----------------------------------------------------------------------
    // Audio generation loop (runs on audioThread)
    // -----------------------------------------------------------------------

    /**
     * Continuously generates audio samples from the PSG model and writes them
     * to the {@link SourceDataLine}.
     *
     * <h3>Clock scheme</h3>
     * <ul>
     *   <li>PSG is clocked at {@value #PSG_CLOCK_HZ} Hz
     *       ({@code en_clk_psg_i=true, sel_n_i=false, reset_n_i=true}).</li>
     *   <li>For each 44.1 kHz output sample, approximately
     *       {@code PSG_CLOCK_HZ / AUDIO_RATE_HZ ≈ 5.67} PSG cycles are
     *       simulated.  An integer phase accumulator distributes cycles as
     *       either 5 or 6 per sample.</li>
     *   <li>Channel outputs are linearly averaged over the simulated cycles,
     *       then summed and scaled to a 16-bit signed PCM value.</li>
     * </ul>
     */
    private void audioLoop() {
        final byte[] buf = new byte[AUDIO_BUF_SAMPS * 2]; // 16-bit mono

        // Phase accumulator: tracks how many PSG cycles are owed to the output.
        // Incremented by PSG_CLOCK_HZ each audio sample, decremented by
        // AUDIO_RATE_HZ for each simulated PSG cycle.
        long phaseAcc   = 0;
        long psgDone    = 0;  // total PSG cycles simulated so far
        long audioDone  = 0;  // total audio samples emitted so far

        // Track the last-applied envelope shape so we only call
        // setEnvelopeShape() (which resets the envelope) when it actually changes.
        int lastEnvShape = -1;

        while (running) {
            // ---------- capture volatile config once per buffer ----------
            PsgConfig c = config;
            applyConfig(c, lastEnvShape);
            if (c.envShape() != lastEnvShape) {
                lastEnvShape = c.envShape();
            }

            // ---------- generate AUDIO_BUF_SAMPS samples ----------
            int bufPos = 0;
            for (int s = 0; s < AUDIO_BUF_SAMPS && running; s++) {
                audioDone++;
                // Number of PSG cycles that should have been simulated by now
                long targetPsg = audioDone * (long) PSG_CLOCK_HZ / AUDIO_RATE_HZ;
                int  cyclesToRun = (int) (targetPsg - psgDone);
                psgDone = targetPsg;

                // Simulate PSG and accumulate channel outputs
                long sumA = 0, sumB = 0, sumC = 0;
                for (int i = 0; i < cyclesToRun; i++) {
                    psg.risingEdge(true, false, true, false, false, 0);
                    sumA += psg.getChAO();
                    sumB += psg.getChBO();
                    sumC += psg.getChCO();
                }

                // Average over simulated cycles (simple box-filter decimation)
                int avgA = cyclesToRun > 0 ? (int) (sumA / cyclesToRun) : 0;
                int avgB = cyclesToRun > 0 ? (int) (sumB / cyclesToRun) : 0;
                int avgC = cyclesToRun > 0 ? (int) (sumC / cyclesToRun) : 0;

                // Linear mix: sum the three channel averages
                int linearSum = avgA + avgB + avgC;

                // Scale to 16-bit signed PCM.
                // linearSum ∈ [0, MAX_DAC_SUM]; map to [-32 768, +32 767].
                int sample = (int) ((long) linearSum * 65535L / MAX_DAC_SUM) - 32768;
                if (sample >  32767) sample =  32767;
                if (sample < -32768) sample = -32768;

                // Little-endian 16-bit output
                buf[bufPos++] = (byte)  (sample        & 0xFF);
                buf[bufPos++] = (byte) ((sample >> 8)  & 0xFF);
            }

            line.write(buf, 0, bufPos);
        }
    }

    /**
     * Push a {@link PsgConfig} snapshot into the PSG register file.
     *
     * <p>Called from the audio thread at the start of each buffer so that UI
     * changes made during the previous buffer take effect immediately.
     *
     * @param c            the config snapshot to apply
     * @param lastEnvShape the envelope shape applied on the previous call
     *                     ({@code -1} on the first call)
     */
    private void applyConfig(PsgConfig c, int lastEnvShape) {
        psg.setTonePeriod(0, c.toneA());
        psg.setTonePeriod(1, c.toneB());
        psg.setTonePeriod(2, c.toneC());

        psg.setVolume(0, c.volA(), c.envModeA());
        psg.setVolume(1, c.volB(), c.envModeB());
        psg.setVolume(2, c.volC(), c.envModeC());

        psg.setMixer(c.toneEnA(), c.toneEnB(), c.toneEnC(),
                     c.noiseEnA(), c.noiseEnB(), c.noiseEnC());

        psg.setNoisePeriod(c.noisePeriod());
        psg.setEnvelopePeriod(c.envPeriod());

        // Only reset the envelope when the shape register changes
        if (c.envShape() != lastEnvShape) {
            psg.setEnvelopeShape(c.envShape());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Format the tone frequency label for a given period value.
     *
     * <p>With the PSG clocked at {@value #PSG_CLOCK_HZ} Hz and
     * {@code sel_n_i = false} (÷2 internal divider), the effective tone
     * frequency is:
     * <pre>
     *   f = PSG_CLOCK_HZ / (2 × 8 × 2 × period)  Hz
     *     = 125 000 / (16 × period)               Hz
     * </pre>
     *
     * @param period tone period register value
     * @return formatted frequency string
     */
    private static String toneFreqLabel(int period) {
        if (period < 6) return "  flatline ";
        double hz = (double) PSG_CLOCK_HZ / (2.0 * 8.0 * 2.0 * period);
        if (hz >= 1000.0) {
            return String.format("%6.2f kHz", hz / 1000.0);
        }
        return String.format("%7.1f Hz", hz);
    }
}
