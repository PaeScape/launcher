/*
 * Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package online.paescape.launcher;

import javax.swing.text.StyleContext;
import java.awt.*;
import java.io.IOException;

class FontManager {
    private static final Font paescapeFont;
    private static final Font paescapeSmallFont;

    static {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();

        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT,
                            FontManager.class.getResourceAsStream("paescape.ttf"))
                    .deriveFont(Font.PLAIN, 16);
            ge.registerFont(font);

            paescapeFont = StyleContext.getDefaultStyleContext()
                    .getFont(font.getName(), Font.PLAIN, 16);
            ge.registerFont(paescapeFont);

            Font smallFont = Font.createFont(Font.TRUETYPE_FONT,
                            FontManager.class.getResourceAsStream("paescape_small.ttf"))
                    .deriveFont(Font.PLAIN, 16);
            ge.registerFont(smallFont);

            paescapeSmallFont = StyleContext.getDefaultStyleContext()
                    .getFont(smallFont.getName(), Font.PLAIN, 16);
            ge.registerFont(paescapeSmallFont);
        } catch (FontFormatException ex) {
            throw new RuntimeException("Font loaded, but format incorrect.", ex);
        } catch (IOException ex) {
            throw new RuntimeException("Font file not found.", ex);
        }
    }

    static Font getPaeScapeFont() {
        return paescapeFont;
    }

    static Font getPaescapeSmallFont() {
        return paescapeSmallFont;
    }
}
