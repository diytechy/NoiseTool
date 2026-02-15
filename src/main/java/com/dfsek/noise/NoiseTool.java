package com.dfsek.noise;

import com.dfsek.noise.platform.DummyPack;
import com.dfsek.noise.platform.PlatformImpl;
import com.dfsek.noise.swing.AdvancedSettingsPanel;
import com.dfsek.noise.swing.NoiseDistributionPanel;
import com.dfsek.noise.swing.NoisePanel;
import com.dfsek.noise.swing.NoiseSettingsPanel;
import com.dfsek.noise.swing.StatusBar;
import com.dfsek.noise.swing.actions.*;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;
import com.dfsek.tectonic.yaml.YamlConfiguration;
import com.dfsek.seismic.type.sampler.Sampler;
import com.dfsek.terra.api.registry.Registry;
import com.dfsek.terra.api.util.reflection.TypeKey;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import net.worldsynth.glpreview.heightmap.Heightmap3DGLPreviewBufferedGL;
import net.worldsynth.glpreview.util.GLUtil;
import net.worldsynth.glpreview.voxel.Blockspace3DGLPreviewBufferedGL;
import org.apache.commons.io.IOUtils;
import org.fife.rsta.ui.CollapsibleSectionPanel;
import org.fife.rsta.ui.search.*;
import org.fife.ui.autocomplete.*;
import org.fife.ui.rsyntaxtextarea.ErrorStrip;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.UIManager.LookAndFeelInfo;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;


public final class NoiseTool extends JFrame implements SearchListener {

    private final CollapsibleSectionPanel csp;
    private final RSyntaxTextArea elevationTextArea;
    private final RSyntaxTextArea colorTextArea;
    private RSyntaxTextArea activeTextArea;
    private final StatusBar statusBar;
    private final JFileChooser fileChooser = new JFileChooser();
    private final JFileChooser imageChooser = new JFileChooser();
    private final NoisePanel noise;
    private final JTextArea sysout;
    private final NoiseSettingsPanel settingsPanel;
    private final AdvancedSettingsPanel advancedPanel;
    private FindDialog findDialog;
    private ReplaceDialog replaceDialog;
    private FindToolBar findToolBar;
    private ReplaceToolBar replaceToolBar;

    // Console filtering
    private TextAreaOutputStream outStream;
    private TextAreaOutputStream errStream;

    // Auto-render state
    private File lastOpenedFile = null;
    private boolean autoRenderEnabled = false;
    private Timer autoRenderTimer;
    private long lastKnownFileModified = 0;

    // Settings persistence
    private static final File SETTINGS_DIR = new File(System.getProperty("user.home"), ".noisetool");
    private static final File SETTINGS_FILE = new File(SETTINGS_DIR, "settings.properties");

    private static final TypeKey<Supplier<ObjectTemplate<Sampler>>> NOISE_REGISTRY_KEY = new TypeKey<>() {};


    private NoiseTool() throws IOException {
        String config = IOUtils.toString(Objects.requireNonNull(NoiseTool.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8);
        initSearchDialogs();

        // Load persisted settings
        Properties settings = loadSettings();

        // Use a border layout as the root layout
        BorderLayout layout = new BorderLayout();
        setLayout(layout);

        // Status bar at the bottom of the window
        statusBar = new StatusBar();
        add(statusBar, BorderLayout.SOUTH);

        // --- Dual editor setup (Elevation + Color) ---
        JPanel textPanel = new JPanel(new BorderLayout());

        // Create both text areas with identical settings
        elevationTextArea = createEditorTextArea();
        colorTextArea = createEditorTextArea();
        activeTextArea = elevationTextArea;

        // Restore editor content from settings, or use defaults
        String savedElevation = settings.getProperty("elevationText", "");
        if (!savedElevation.isEmpty()) {
            elevationTextArea.setText(savedElevation);
            elevationTextArea.setCaretPosition(0);
        } else {
            elevationTextArea.setText(config);
            elevationTextArea.setCaretPosition(0);
        }
        String savedColor = settings.getProperty("colorText", "");
        colorTextArea.setText(savedColor);
        colorTextArea.setCaretPosition(0);

        // Build editor panels for each tab
        JPanel elevationEditorPanel = new JPanel(new BorderLayout());
        elevationEditorPanel.add(new RTextScrollPane(elevationTextArea), BorderLayout.CENTER);
        elevationEditorPanel.add(new ErrorStrip(elevationTextArea), BorderLayout.LINE_END);

        JPanel colorEditorPanel = new JPanel(new BorderLayout());
        colorEditorPanel.add(new RTextScrollPane(colorTextArea), BorderLayout.CENTER);
        colorEditorPanel.add(new ErrorStrip(colorTextArea), BorderLayout.LINE_END);

        // CardLayout to switch between editors
        CardLayout editorCardLayout = new CardLayout();
        JPanel editorCards = new JPanel(editorCardLayout);
        editorCards.add(elevationEditorPanel, "Elevation");
        editorCards.add(colorEditorPanel, "Color");

        // Editor tab buttons
        JPanel editorTabRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ButtonGroup editorTabGroup = new ButtonGroup();

        JToggleButton elevationTab = new JToggleButton("Elevation");
        elevationTab.setFocusPainted(false);
        elevationTab.setMargin(new Insets(4, 12, 4, 12));
        elevationTab.setSelected(true);
        editorTabGroup.add(elevationTab);
        editorTabRow.add(elevationTab);

        JToggleButton colorTab = new JToggleButton("Color");
        colorTab.setFocusPainted(false);
        colorTab.setMargin(new Insets(4, 12, 4, 12));
        editorTabGroup.add(colorTab);
        editorTabRow.add(colorTab);

        elevationTab.addActionListener(e -> {
            editorCardLayout.show(editorCards, "Elevation");
            activeTextArea = elevationTextArea;
        });
        colorTab.addActionListener(e -> {
            editorCardLayout.show(editorCards, "Color");
            activeTextArea = colorTextArea;
        });

        // Wrap editor cards in CollapsibleSectionPanel for find/replace toolbars
        csp = new CollapsibleSectionPanel();
        csp.add(editorCards);

        textPanel.add(editorTabRow, BorderLayout.NORTH);
        textPanel.add(csp, BorderLayout.CENTER);

        settingsPanel = new NoiseSettingsPanel(settings);
        advancedPanel = new AdvancedSettingsPanel(settings);

        // Noise panels and other stuff at the right side
        PlatformImpl platform = new PlatformImpl();
        DummyPack pack = new DummyPack(platform, new YamlConfiguration(config, "Noise Config"), advancedPanel.isUseLetExpressions());

        CompletionProvider provider = createCompletionProvider(pack.getRegistry(NOISE_REGISTRY_KEY));

        AutoCompletion acElevation = new AutoCompletion(provider);
        acElevation.install(elevationTextArea);
        acElevation.setShowDescWindow(true);
        acElevation.setAutoCompleteEnabled(true);
        acElevation.setAutoActivationEnabled(true);
        acElevation.setAutoCompleteSingleChoices(false);
        acElevation.setAutoActivationDelay(200);

        AutoCompletion acColor = new AutoCompletion(provider);
        acColor.install(colorTextArea);
        acColor.setShowDescWindow(true);
        acColor.setAutoCompleteEnabled(true);
        acColor.setAutoActivationEnabled(true);
        acColor.setAutoCompleteSingleChoices(false);
        acColor.setAutoActivationDelay(200);

        NoiseDistributionPanel distributionPanel = new NoiseDistributionPanel();

        GLUtil.logGLProfiles();
        Heightmap3DGLPreviewBufferedGL noise3d = new Heightmap3DGLPreviewBufferedGL();
        Blockspace3DGLPreviewBufferedGL noise3dVox = new Blockspace3DGLPreviewBufferedGL();

        this.noise = new NoisePanel(elevationTextArea, colorTextArea, noise3d, noise3dVox, distributionPanel, settingsPanel, advancedPanel, platform, statusBar);

        // Console setup
        sysout = new JTextArea();
        sysout.setEditable(false);

        outStream = new TextAreaOutputStream(sysout);
        errStream = new TextAreaOutputStream(sysout);
        boolean verbose = advancedPanel.isEditorVerboseConsole();
        outStream.setPassThrough(verbose);
        errStream.setPassThrough(verbose);
        System.setOut(new PrintStream(outStream));
        System.setErr(new PrintStream(errStream));

        // Wire verbose console toggle
        advancedPanel.setOnVerboseConsoleChanged(() -> {
            boolean v = advancedPanel.isEditorVerboseConsole();
            outStream.setPassThrough(v);
            errStream.setPassThrough(v);
        });

        // Wire console output for direct logging (bypasses filter)
        noise.setConsoleOutput(sysout);

        // Tab setup - two fixed rows of toggle buttons with CardLayout content
        CardLayout cardLayout = new CardLayout();
        JPanel contentCards = new JPanel(cardLayout);
        ButtonGroup tabGroup = new ButtonGroup();

        JPanel tabStrips = new JPanel();
        tabStrips.setLayout(new BoxLayout(tabStrips, BoxLayout.Y_AXIS));

        // Row 1: Render tabs (top)
        JPanel renderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addTabButton(renderRow, tabGroup, contentCards, cardLayout, "Render", noise, true);
        addTabButton(renderRow, tabGroup, contentCards, cardLayout, "Render 3D", noise3d, false);
        addTabButton(renderRow, tabGroup, contentCards, cardLayout, "Render Voxel", noise3dVox, false);
        tabStrips.add(renderRow);

        // Row 2: Context tabs (bottom)
        JPanel contextRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addTabButton(contextRow, tabGroup, contentCards, cardLayout, "Settings", settingsPanel, false);
        addTabButton(contextRow, tabGroup, contentCards, cardLayout, "Advanced", advancedPanel, false);
        addTabButton(contextRow, tabGroup, contentCards, cardLayout, "Distribution", distributionPanel, false);
        addTabButton(contextRow, tabGroup, contentCards, cardLayout, "Console", new JScrollPane(sysout), false);
        tabStrips.add(contextRow);

        // Combine tab strips and content area
        JPanel tabbedPanel = new JPanel(new BorderLayout());
        tabbedPanel.add(tabStrips, BorderLayout.NORTH);
        tabbedPanel.add(contentCards, BorderLayout.CENTER);
        tabbedPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));

        GridLayout gridLayout = new GridLayout(1, 2);
        JPanel contentPanel = new JPanel(gridLayout);

        add(contentPanel, BorderLayout.CENTER);

        contentPanel.add(textPanel);
        contentPanel.add(tabbedPanel);


        setJMenuBar(createMenuBar());


        setTitle("Noise Tool");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        // Save settings on window close
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveSettings();
                dispose();
                System.exit(0);
            }
        });

        FlatDarculaLaf.setup();

        SwingUtilities.updateComponentTreeUI(NoiseTool.this);
        if(findDialog != null) {
            findDialog.updateUI();
            replaceDialog.updateUI();
        }
        pack();

        pack();
        setLocationRelativeTo(null);

        noise.reload();
        noise.update();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch(Exception e) {
                e.printStackTrace();
            }
            try {
                new NoiseTool().setVisible(true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private CompletionProvider createCompletionProvider(Registry<Supplier<ObjectTemplate<Sampler>>> registry) {
        DefaultCompletionProvider noiseTypeProvider = new DefaultCompletionProvider();
        noiseTypeProvider.setAutoActivationRules(true, null);

        registry.keys().forEach(key -> noiseTypeProvider.addCompletion(new BasicCompletion(noiseTypeProvider, key.toString(), null, key + " noise type")));

        DefaultCompletionProvider basicProvider = new DefaultCompletionProvider();
        basicProvider.setAutoActivationRules(true, null);

        registry.keys().forEach(key -> basicProvider.addCompletion(new BasicCompletion(basicProvider, key.toString(), null, key + " noise type")));
        basicProvider.addCompletion(new BasicCompletion(basicProvider, "type", null, "Sets the noise type for this sampler."));
        basicProvider.addCompletion(new BasicCompletion(basicProvider, "frequency", null, "Sets the frequency for this sampler."));

        LanguageAwareCompletionProvider provider = new LanguageAwareCompletionProvider(basicProvider);
        basicProvider.setAutoActivationRules(true, null);

        provider.setStringCompletionProvider(noiseTypeProvider);

        return provider;

    }

    private static RSyntaxTextArea createEditorTextArea() {
        RSyntaxTextArea ta = new RSyntaxTextArea(35, 45);
        ta.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_YAML);
        ta.setCodeFoldingEnabled(true);
        ta.setMarkOccurrences(true);
        ta.setTabsEmulated(true);
        ta.setTabSize(2);
        return ta;
    }

    private static void addTabButton(JPanel row, ButtonGroup group, JPanel cards, CardLayout layout, String name, Component content, boolean selected) {
        JToggleButton btn = new JToggleButton(name);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(4, 12, 4, 12));
        group.add(btn);
        row.add(btn);
        cards.add(content, name);
        btn.addActionListener(e -> layout.show(cards, name));
        if (selected) {
            btn.setSelected(true);
        }
    }

    public JFileChooser getFileChooser() {
        return fileChooser;
    }

    private void addItem(Action a, ButtonGroup bg, JMenu menu) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(a);
        bg.add(item);
        menu.add(item);
    }

    private JMenuBar createMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu menu = new JMenu("File");

        Action open = new OpenFileAction(this);
        Action save = new SaveAction(this);
        Action saveAs = new SaveAsAction(this);
        Action saveRender = new SaveRenderAsAction(this);

        open.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_MASK));
        save.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_MASK));
        saveAs.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_MASK | KeyEvent.SHIFT_MASK));
        saveRender.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_MASK | KeyEvent.ALT_MASK));

        menu.add(open);
        menu.add(save);
        menu.add(saveAs);
        menu.add(saveRender);


        mb.add(menu);

        menu = new JMenu("Search");
        menu.add(new JMenuItem(new ShowFindDialogAction(this)));
        menu.add(new JMenuItem(new ShowReplaceDialogAction(this)));
        menu.add(new JMenuItem(new GoToLineAction(this)));
        menu.addSeparator();

        int ctrl = getToolkit().getMenuShortcutKeyMask();
        int shift = InputEvent.SHIFT_MASK;
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_F, ctrl | shift);
        Action a = csp.addBottomComponent(ks, findToolBar);
        a.putValue(Action.NAME, "Show Find Search Bar");
        menu.add(new JMenuItem(a));
        ks = KeyStroke.getKeyStroke(KeyEvent.VK_H, ctrl | shift);
        a = csp.addBottomComponent(ks, replaceToolBar);
        a.putValue(Action.NAME, "Show Replace Search Bar");
        menu.add(new JMenuItem(a));

        mb.add(menu);

        menu = new JMenu("Theme");
        ButtonGroup bg = new ButtonGroup();
        FlatLightLaf.installLafInfo();
        FlatDarculaLaf.installLafInfo();
        FlatDarkLaf.installLafInfo();
        LookAndFeelInfo[] infos = UIManager.getInstalledLookAndFeels();
        for(LookAndFeelInfo info : infos) {
            addItem(new LookAndFeelAction(this, info), bg, menu);
        }
        mb.add(menu);

        menu = new JMenu("Noise");

        Action up = new UpdateNoiseAction(noise);

        up.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        menu.add(up);
        menu.add(new MutableBooleanAction(noise.getChunk(), "Toggle Chunk Borders"));

        // Auto-render toggle (F6)
        Action autoRender = new ToggleAutoRenderAction(this);
        autoRender.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0));
        menu.add(autoRender);

        mb.add(menu);

        return mb;

    }

    // --- Auto-render functionality ---

    public void toggleAutoRender() {
        autoRenderEnabled = !autoRenderEnabled;

        if (autoRenderEnabled) {
            if (lastOpenedFile == null) {
                consoleLog("No file opened. Open a file first before enabling auto-render.");
                autoRenderEnabled = false;
                return;
            }

            lastKnownFileModified = lastOpenedFile.lastModified();
            statusBar.setAutoRenderStatus(true);

            // Set up the render callback
            noise.setRenderCallback((success, renderTimeMs) -> {
                if (!autoRenderEnabled) return;

                String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS").format(new Date());

                // Auto-save log
                autoSaveLog(timestamp);

                // Auto-save render image (only on success)
                if (success) {
                    autoSaveRender(timestamp);
                }

                // Schedule next check after 5 seconds
                scheduleAutoRenderCheck();
            });

            consoleLog("[Auto-render] Enabled. Watching: " + lastOpenedFile.getAbsolutePath());

            // Start the first check
            scheduleAutoRenderCheck();
        } else {
            if (autoRenderTimer != null) {
                autoRenderTimer.stop();
            }
            noise.setRenderCallback(null);
            statusBar.setAutoRenderStatus(false);
            consoleLog("[Auto-render] Disabled.");
        }
    }

    private void scheduleAutoRenderCheck() {
        if (autoRenderTimer != null) {
            autoRenderTimer.stop();
        }
        autoRenderTimer = new Timer(5000, e -> checkFileAndRerender());
        autoRenderTimer.setRepeats(false);
        autoRenderTimer.start();
    }

    private void checkFileAndRerender() {
        if (!autoRenderEnabled || lastOpenedFile == null) return;

        long currentModified = lastOpenedFile.lastModified();
        if (currentModified != lastKnownFileModified) {
            lastKnownFileModified = currentModified;
            consoleLog("[Auto-render] File changed, reloading...");

            // Read file into text area
            try {
                elevationTextArea.setText(IOUtils.toString(new FileInputStream(lastOpenedFile), Charset.defaultCharset()));
            } catch (IOException ex) {
                ex.printStackTrace();
                scheduleAutoRenderCheck();
                return;
            }

            // Clear console before auto-render
            sysout.setText("");

            // Trigger render -- callback will handle post-render actions
            noise.renderAsync();
        } else {
            // File hasn't changed, schedule another check
            scheduleAutoRenderCheck();
        }
    }

    private void autoSaveLog(String timestamp) {
        if (lastOpenedFile == null) return;

        File outputDir = new File(lastOpenedFile.getParentFile(), "auto_output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File logFile = new File(outputDir, timestamp + "_log.txt");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write(sysout.getText());
            consoleLog("[Auto-render] Log saved to " + logFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void autoSaveRender(String timestamp) {
        if (lastOpenedFile == null) return;

        BufferedImage render = noise.getRender();
        if (render == null) return;

        File outputDir = new File(lastOpenedFile.getParentFile(), "auto_output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File imageFile = new File(outputDir, timestamp + "_render.png");
        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            ImageIO.write(render, "png", fos);
            consoleLog("[Auto-render] Render saved to " + imageFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- Console logging (bypasses filter) ---

    private void consoleLog(String message) {
        SwingUtilities.invokeLater(() -> {
            sysout.append(message + "\n");
            sysout.setCaretPosition(sysout.getDocument().getLength());
        });
    }

    // --- Settings persistence ---

    private static Properties loadSettings() {
        Properties props = new Properties();
        if (SETTINGS_FILE.exists()) {
            try (FileInputStream fis = new FileInputStream(SETTINGS_FILE)) {
                props.load(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return props;
    }

    private void saveSettings() {
        Properties props = new Properties();

        // Basic settings
        props.setProperty("seed", String.valueOf(settingsPanel.getSeed()));
        props.setProperty("originX", String.valueOf(settingsPanel.getOriginX()));
        props.setProperty("originZ", String.valueOf(settingsPanel.getOriginZ()));
        props.setProperty("perspectiveMultiplier", String.valueOf(settingsPanel.getPerspectiveMultiplier()));
        props.setProperty("colorScalePreset", settingsPanel.getColorScalePresetName());
        props.setProperty("colorScaleNormalized", String.valueOf(settingsPanel.isColorScaleNormalized()));
        props.setProperty("colorScaleText", settingsPanel.getColorScaleText());

        // Advanced settings
        props.setProperty("useLetExpressions", String.valueOf(advancedPanel.isUseLetExpressions()));
        props.setProperty("voxelResolution", String.valueOf(advancedPanel.getVoxelResolution()));
        props.setProperty("voxelBottomY", String.valueOf(advancedPanel.getVoxelBottomY()));
        props.setProperty("voxelTopY", String.valueOf(advancedPanel.getVoxelTopY()));
        props.setProperty("editorVerboseConsole", String.valueOf(advancedPanel.isEditorVerboseConsole()));

        // Editor contents
        props.setProperty("elevationText", elevationTextArea.getText());
        props.setProperty("colorText", colorTextArea.getText());

        if (!SETTINGS_DIR.exists()) {
            SETTINGS_DIR.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
            props.store(fos, "NoiseTool Settings");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- File tracking ---

    public File getLastOpenedFile() {
        return lastOpenedFile;
    }

    public void setLastOpenedFile(File file) {
        this.lastOpenedFile = file;
    }

    public JTextArea getSysout() {
        return sysout;
    }

    // --- Search functionality ---

    @Override
    public String getSelectedText() {
        return activeTextArea.getSelectedText();
    }

    public NoisePanel getNoise() {
        return noise;
    }

    /**
     * Creates our Find and Replace dialogs.
     */
    private void initSearchDialogs() {

        findDialog = new FindDialog(this, this);
        replaceDialog = new ReplaceDialog(this, this);

        // This ties the properties of the two dialogs together (match case,
        // regex, etc.).
        SearchContext context = findDialog.getSearchContext();
        replaceDialog.setSearchContext(context);

        // Create tool bars and tie their search contexts together also.
        findToolBar = new FindToolBar(this);
        findToolBar.setSearchContext(context);
        replaceToolBar = new ReplaceToolBar(this);
        replaceToolBar.setSearchContext(context);

    }

    /**
     * Listens for events from our search dialogs and actually does the dirty
     * work.
     */
    @Override
    public void searchEvent(SearchEvent e) {

        SearchEvent.Type type = e.getType();
        SearchContext context = e.getSearchContext();
        SearchResult result;

        switch(type) {
            default: // Prevent FindBugs warning later
            case MARK_ALL:
                result = SearchEngine.markAll(activeTextArea, context);
                break;
            case FIND:
                result = SearchEngine.find(activeTextArea, context);
                if(!result.wasFound() || result.isWrapped()) {
                    UIManager.getLookAndFeel().provideErrorFeedback(activeTextArea);
                }
                break;
            case REPLACE:
                result = SearchEngine.replace(activeTextArea, context);
                if(!result.wasFound() || result.isWrapped()) {
                    UIManager.getLookAndFeel().provideErrorFeedback(activeTextArea);
                }
                break;
            case REPLACE_ALL:
                result = SearchEngine.replaceAll(activeTextArea, context);
                JOptionPane.showMessageDialog(null, result.getCount() +
                        " occurrences replaced.");
                break;
        }

        String text;
        if(result.wasFound()) {
            text = "Text found; occurrences marked: " + result.getMarkedCount();
        } else if(type == SearchEvent.Type.MARK_ALL) {
            if(result.getMarkedCount() > 0) {
                text = "Occurrences marked: " + result.getMarkedCount();
            } else {
                text = "";
            }
        } else {
            text = "Text not found";
        }
        statusBar.setLabel(text);

    }

    public ReplaceDialog getReplaceDialog() {
        return replaceDialog;
    }

    public FindDialog getFindDialog() {
        return findDialog;
    }

    public RSyntaxTextArea getTextArea() {
        return activeTextArea;
    }

    public RSyntaxTextArea getElevationTextArea() {
        return elevationTextArea;
    }

    public RSyntaxTextArea getColorTextArea() {
        return colorTextArea;
    }

    public JFileChooser getImageChooser() {
        return imageChooser;
    }
}
