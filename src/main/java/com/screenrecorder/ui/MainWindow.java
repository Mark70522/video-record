package com.screenrecorder.ui;

import com.screenrecorder.AppConfig;
import com.screenrecorder.recorder.RecordingSession;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class MainWindow extends JFrame {

    // colors
    private static final Color C_BG       = new Color(18,  18,  18);
    private static final Color C_SURFACE  = new Color(28,  28,  30);
    private static final Color C_BORDER   = new Color(50,  50,  55);
    private static final Color C_TEXT     = new Color(240, 240, 240);
    private static final Color C_SUBTEXT  = new Color(140, 140, 145);
    private static final Color C_RED      = new Color(255,  69,  58);
    private static final Color C_GREEN    = new Color( 50, 215, 100);
    private static final Color C_ORANGE   = new Color(255, 159,  10);
    private static final Color C_BLUE     = new Color( 10, 132, 255);
    private static final Color C_BTN_STOP = new Color( 60,  20,  20);

    private final AppConfig config = new AppConfig();
    private RecordingSession session;

    // ui
    private JLabel         lblTimer;
    private JLabel         lblStatus;
    private JLabel         lblDot;
    private JLabel         lblSavePath;
    private JLabel         lblMicInfo;
    private JLabel         lblLoopbackInfo;
    private JTextField     tfFfmpeg;
    private RoundedButton  btnRecord;
    private RoundedButton  btnStop;
    private Timer          uiTimer;
    private Timer          dotTimer;
    private boolean        dotVisible = true;

    public MainWindow() {
        session = new RecordingSession(config.getOutputDir());
        buildUI();
        setupWindowEvents();
        startUiTimer();
        refreshDeviceLabels();
    }

    // ------------------------------------------------------------------ build

    private void buildUI() {
        setTitle("Screen Recorder");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setResizable(false);
        setBackground(C_BG);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(C_BG);
        root.setBorder(new EmptyBorder(24, 24, 20, 24));
        setContentPane(root);

        root.add(buildTopSection(),    BorderLayout.NORTH);
        root.add(buildMiddleSection(), BorderLayout.CENTER);
        root.add(buildBottomSection(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(460, 0));
        setSize(460, 480);
        setLocationRelativeTo(null);
    }

    private JPanel buildTopSection() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_BG);
        p.setBorder(new EmptyBorder(0, 0, 18, 0));

        JLabel title = new JLabel("Screen Recorder");
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        title.setForeground(C_TEXT);

        lblDot = new JLabel("  REC");
        lblDot.setFont(new Font("SansSerif", Font.BOLD, 11));
        lblDot.setForeground(C_RED);
        lblDot.setVisible(false);

        p.add(title,  BorderLayout.WEST);
        p.add(lblDot, BorderLayout.EAST);
        return p;
    }

    private JPanel buildMiddleSection() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 12));
        wrapper.setBackground(C_BG);

        // Timer card
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(C_SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 1),
                new EmptyBorder(18, 20, 18, 20)));

        lblTimer = new JLabel("00:00:00", SwingConstants.CENTER);
        lblTimer.setFont(new Font("Consolas", Font.BOLD, 52));
        lblTimer.setForeground(C_TEXT);

        lblStatus = new JLabel("Ready to record", SwingConstants.CENTER);
        lblStatus.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblStatus.setForeground(C_SUBTEXT);

        card.add(lblTimer,  BorderLayout.CENTER);
        card.add(lblStatus, BorderLayout.SOUTH);

        // Device info card
        JPanel devCard = new JPanel(new GridLayout(2, 1, 0, 4));
        devCard.setBackground(C_SURFACE);
        devCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 1),
                new EmptyBorder(10, 14, 10, 14)));

        lblMicInfo      = makeDevLabel("Microphone",   "detecting...");
        lblLoopbackInfo = makeDevLabel("System audio", "detecting...");
        devCard.add(lblMicInfo);
        devCard.add(lblLoopbackInfo);

        // Settings card (save path + ffmpeg path)
        JPanel settingsCard = new JPanel(new GridLayout(2, 1, 0, 6));
        settingsCard.setBackground(C_SURFACE);
        settingsCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 1),
                new EmptyBorder(10, 14, 10, 14)));

        settingsCard.add(buildSavePathRow());
        settingsCard.add(buildFfmpegRow());

        wrapper.add(card,         BorderLayout.NORTH);
        wrapper.add(devCard,      BorderLayout.CENTER);
        wrapper.add(settingsCard, BorderLayout.SOUTH);
        return wrapper;
    }

    private JPanel buildSavePathRow() {
        lblSavePath = new JLabel(shortenPath(config.getOutputDir(), 42));
        lblSavePath.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblSavePath.setForeground(C_SUBTEXT);

        JButton btn = linkButton("Change");
        btn.addActionListener(e -> chooseOutputDir());

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(C_SURFACE);

        JLabel lbl = new JLabel("Save to:");
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lbl.setForeground(C_SUBTEXT);
        lbl.setBorder(new EmptyBorder(0, 0, 0, 6));

        row.add(lbl,        BorderLayout.WEST);
        row.add(lblSavePath, BorderLayout.CENTER);
        row.add(btn,        BorderLayout.EAST);
        return row;
    }

    private JPanel buildFfmpegRow() {
        JLabel lbl = new JLabel("FFmpeg:");
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lbl.setForeground(C_SUBTEXT);
        lbl.setBorder(new EmptyBorder(0, 0, 0, 6));

        tfFfmpeg = new JTextField(config.getFfmpegPath());
        tfFfmpeg.setFont(new Font("Consolas", Font.PLAIN, 11));
        tfFfmpeg.setForeground(C_TEXT);
        tfFfmpeg.setBackground(new Color(38, 38, 40));
        tfFfmpeg.setCaretColor(C_TEXT);
        tfFfmpeg.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 1),
                new EmptyBorder(2, 6, 2, 6)));
        tfFfmpeg.setToolTipText("Full path to ffmpeg.exe, or blank to use ffmpeg from system PATH");

        // Save path to config when user finishes editing
        tfFfmpeg.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { saveFfmpegPath(); }
        });

        JButton btn = linkButton("Browse...");
        btn.addActionListener(e -> browseFfmpeg());

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(C_SURFACE);
        row.add(lbl,      BorderLayout.WEST);
        row.add(tfFfmpeg, BorderLayout.CENTER);
        row.add(btn,      BorderLayout.EAST);
        return row;
    }

    private JPanel buildBottomSection() {
        JPanel p = new JPanel(new GridLayout(1, 2, 12, 0));
        p.setBackground(C_BG);
        p.setBorder(new EmptyBorder(16, 0, 0, 0));

        btnRecord = new RoundedButton("Start Recording", C_GREEN, Color.WHITE);
        btnStop   = new RoundedButton("Stop",            C_BTN_STOP, C_RED);
        btnStop.setEnabled(false);

        btnRecord.addActionListener(e -> onRecordButton());
        btnStop.addActionListener(e   -> onStop());

        p.add(btnRecord);
        p.add(btnStop);
        return p;
    }

    // ------------------------------------------------------------------ logic

    private void onRecordButton() {
        switch (session.getState()) {
            case IDLE:
                startRecording();
                break;

            case RECORDING:
                pauseRecording();
                break;

            case PAUSED:
                resumeRecording();
                break;

            default:
                break;
        }
    }

    private void startRecording() {
        saveFfmpegPath();
        session.setFfmpegPath(config.getFfmpegPath());
        try {
            session.start();
            btnRecord.setText("Pause");
            btnRecord.setColors(C_ORANGE, Color.WHITE);
            btnStop.setEnabled(true);
            lblStatus.setText("Recording...");
            lblStatus.setForeground(C_RED);
            lblDot.setVisible(true);
            startDotBlink();
        } catch (Exception ex) {
            showError("Failed to start recording", ex.getMessage());
        }
    }

    private void pauseRecording() {
        session.pause();
        btnRecord.setText("Resume");
        btnRecord.setColors(C_GREEN, Color.WHITE);
        lblStatus.setText("Paused");
        lblStatus.setForeground(C_ORANGE);
        stopDotBlink();
        lblDot.setVisible(false);
    }

    private void resumeRecording() {
        session.resume();
        btnRecord.setText("Pause");
        btnRecord.setColors(C_ORANGE, Color.WHITE);
        lblStatus.setText("Recording...");
        lblStatus.setForeground(C_RED);
        lblDot.setVisible(true);
        startDotBlink();
    }

    private void onStop() {
        stopDotBlink();
        lblStatus.setText("Saving...");
        lblStatus.setForeground(C_SUBTEXT);
        btnRecord.setEnabled(false);
        btnStop.setEnabled(false);

        // Run stop on a background thread (FFmpeg mux can take a few seconds)
        new Thread(() -> {
            try {
                String saved = session.stop();
                SwingUtilities.invokeLater(() -> {
                    resetUI();
                    if (saved != null) {
                        // 录制结束自动保存,不再弹确认框;仅状态栏轻提示已保存
                        lblStatus.setText("Saved: " + new File(saved).getName());
                        lblStatus.setForeground(C_GREEN);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    resetUI();
                    showError("Failed to stop recording", ex.getMessage());
                });
            }
        }, "Stop-Thread").start();
    }

    private void resetUI() {
        btnRecord.setText("Start Recording");
        btnRecord.setColors(C_GREEN, Color.WHITE);
        btnRecord.setEnabled(true);
        btnStop.setEnabled(false);
        lblStatus.setText("Ready to record");
        lblStatus.setForeground(C_SUBTEXT);
        lblTimer.setText("00:00:00");
        lblDot.setVisible(false);
    }

    // ------------------------------------------------------------------ helpers

    private void saveFfmpegPath() {
        String path = tfFfmpeg.getText().trim();
        config.setFfmpegPath(path);   // auto-saves to config file
    }

    private void chooseOutputDir() {
        JFileChooser fc = new JFileChooser(config.getOutputDir());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Choose Output Folder");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String dir = fc.getSelectedFile().getAbsolutePath();
            config.setOutputDir(dir);
            session = new RecordingSession(dir);
            session.setFfmpegPath(config.getFfmpegPath());
            lblSavePath.setText(shortenPath(dir, 42));
            refreshDeviceLabels();
        }
    }

    private void browseFfmpeg() {
        String startDir = config.getFfmpegPath().isEmpty()
                ? System.getenv("ProgramFiles")
                : new File(config.getFfmpegPath()).getParent();
        JFileChooser fc = new JFileChooser(startDir);
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setDialogTitle("Select ffmpeg.exe");
        fc.setFileFilter(new FileNameExtensionFilter("Executable (*.exe)", "exe"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = fc.getSelectedFile().getAbsolutePath();
            tfFfmpeg.setText(path);
            config.setFfmpegPath(path);   // save immediately on browse
        }
    }

    private void refreshDeviceLabels() {
        SwingUtilities.invokeLater(() -> {
            String mic = session.getMicDeviceName();
            updateDevLabel(lblMicInfo, "Microphone",
                    mic != null ? shorten(mic, 38) : "not found");
            updateDevLabel(lblLoopbackInfo, "System audio",
                    session.isWasapiAvailable()
                            ? "WASAPI loopback"
                            : "not available");
        });
    }

    private void showError(String title, String msg) {
        JOptionPane.showMessageDialog(this, msg, title, JOptionPane.ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------ timer / blink

    private void startUiTimer() {
        uiTimer = new Timer(500, e -> {
            if (session.getState() != RecordingSession.State.IDLE) {
                long ms = session.getElapsedMs();
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                lblTimer.setText(sdf.format(new Date(ms)));
            }
        });
        uiTimer.start();
    }

    private void startDotBlink() {
        if (dotTimer != null && dotTimer.isRunning()) return;
        dotTimer = new Timer(600, e -> {
            dotVisible = !dotVisible;
            lblDot.setVisible(dotVisible);
        });
        dotTimer.start();
    }

    private void stopDotBlink() {
        if (dotTimer != null) dotTimer.stop();
        dotVisible = true;
    }

    // ------------------------------------------------------------------ window

    private void setupWindowEvents() {
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (session.getState() != RecordingSession.State.IDLE) {
                    int r = JOptionPane.showConfirmDialog(MainWindow.this,
                            "Recording is in progress. Stop and save before closing?",
                            "Confirm Exit", JOptionPane.YES_NO_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (r == JOptionPane.YES_OPTION) {
                        try { session.stop(); } catch (Exception ex) { ex.printStackTrace(); }
                        System.exit(0);
                    } else if (r == JOptionPane.NO_OPTION) {
                        System.exit(0);
                    }
                } else {
                    System.exit(0);
                }
            }
        });
    }

    // ------------------------------------------------------------------ small utils

    private JLabel makeDevLabel(String kind, String value) {
        JLabel lbl = new JLabel();
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lbl.setForeground(C_SUBTEXT);
        updateDevLabel(lbl, kind, value);
        return lbl;
    }

    private void updateDevLabel(JLabel lbl, String kind, String value) {
        lbl.setText("<html><span style='color:#8e8e93'>" + kind + ":&nbsp;</span>"
                + "<span style='color:#e0e0e0'>" + (value != null ? value : "not found")
                + "</span></html>");
    }

    private static JButton linkButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("SansSerif", Font.PLAIN, 11));
        b.setForeground(new Color(10, 132, 255));
        b.setBackground(null);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "...";
    }

    private static String shortenPath(String path, int max) {
        if (path.length() <= max) return path;
        // Keep the last part visible
        String end = "..." + path.substring(path.length() - (max - 3));
        return end;
    }

    // ------------------------------------------------------------------ RoundedButton

    static class RoundedButton extends JButton {
        private Color bg, fg;
        private static final int ARC = 10;

        RoundedButton(String text, Color bg, Color fg) {
            super(text);
            setColors(bg, fg);
            setFont(new Font("SansSerif", Font.BOLD, 13));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(160, 46));
        }

        void setColors(Color bg, Color fg) {
            this.bg = bg; this.fg = fg;
            setForeground(fg);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = isEnabled() ? bg : bg.darker();
            if      (getModel().isPressed())  base = base.darker();
            else if (getModel().isRollover()) base = base.brighter();
            g2.setColor(base);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
