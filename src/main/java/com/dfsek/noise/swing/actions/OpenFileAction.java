package com.dfsek.noise.swing.actions;

import com.dfsek.noise.NoiseTool;
import org.apache.commons.io.IOUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;

public class OpenFileAction extends AbstractAction {
    private final NoiseTool noiseTool;

    public OpenFileAction(NoiseTool noiseTool) {
        super("Open");
        this.noiseTool = noiseTool;
    }


    @Override
    public void actionPerformed(ActionEvent actionEvent) {
        File selectedFile = null;

        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            // Use native Windows file dialog for quick links and navigation
            FileDialog fd = new FileDialog(noiseTool, "Open File", FileDialog.LOAD);
            File lastFile = noiseTool.getFileChooser().getSelectedFile();
            if (lastFile != null && lastFile.getParentFile() != null) {
                fd.setDirectory(lastFile.getParentFile().getAbsolutePath());
            }
            fd.setVisible(true);

            String fileName = fd.getFile();
            String dirName = fd.getDirectory();
            if (fileName != null && dirName != null) {
                selectedFile = new File(dirName, fileName);
                // Keep JFileChooser in sync for Save action
                noiseTool.getFileChooser().setSelectedFile(selectedFile);
            }
        } else {
            // Use Swing file chooser on non-Windows
            int returnVal = noiseTool.getFileChooser().showOpenDialog(noiseTool);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                selectedFile = noiseTool.getFileChooser().getSelectedFile();
            }
        }

        if (selectedFile != null) {
            System.out.println("Opening " + selectedFile.getAbsolutePath());
            try {
                noiseTool.getTextArea().setText(IOUtils.toString(new FileInputStream(selectedFile), Charset.defaultCharset()));
                noiseTool.setLastOpenedFile(selectedFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("Operation cancelled by user.");
        }
    }
}
