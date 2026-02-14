package com.dfsek.noise;

import javax.swing.*;
import java.io.OutputStream;

public class TextAreaOutputStream extends OutputStream {
    private final JTextArea textArea;
    private volatile boolean passThrough = false;

    public TextAreaOutputStream(JTextArea textArea) {
        this.textArea = textArea;
    }

    public void setPassThrough(boolean passThrough) {
        this.passThrough = passThrough;
    }

    @Override
    public void write(int b) {
        if (!passThrough) return;
        textArea.append(String.valueOf((char) b));
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }
}
