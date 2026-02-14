package com.dfsek.noise.swing.actions;

import com.dfsek.noise.NoiseTool;

import javax.swing.*;
import java.awt.event.ActionEvent;

public class ToggleAutoRenderAction extends AbstractAction {
    private final NoiseTool noiseTool;

    public ToggleAutoRenderAction(NoiseTool noiseTool) {
        super("Toggle Auto-Render");
        this.noiseTool = noiseTool;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        noiseTool.toggleAutoRender();
    }
}
