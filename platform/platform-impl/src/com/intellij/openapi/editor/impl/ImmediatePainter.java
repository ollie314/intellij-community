/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.editor.impl;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.ex.util.EditorUIUtil;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.util.Consumer;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.*;
import java.util.List;

/**
 * @author Pavel Fatin
 */
class ImmediatePainter {
  private static final Set<Character> KEY_CHARS_TO_SKIP =
    new HashSet<>(Arrays.asList('\n', '\t', '(', ')', '[', ']', '{', '}', '"', '\''));

  private static final int DEBUG_PAUSE_DURATION = 1000;

  private static final boolean VIM_PLUGIN_LOADED = isPluginLoaded("IdeaVIM");

  static final RegistryValue ENABLED = Registry.get("editor.zero.latency.rendering");
  static final RegistryValue DOUBLE_BUFFERING = Registry.get("editor.zero.latency.rendering.double.buffering");
  private static final RegistryValue PIPELINE_FLUSH = Registry.get("editor.zero.latency.rendering.pipeline.flush");
  private static final RegistryValue DEBUG = Registry.get("editor.zero.latency.rendering.debug");

  private final EditorImpl myEditor;
  private Image myImage;

  ImmediatePainter(EditorImpl editor) {
    myEditor = editor;

    Disposer.register(editor.getDisposable(), () -> {
      if (myImage != null) {
        myImage.flush();
      }
    });
  }

  void paintCharacter(final Graphics g, final char c) {
    final EditorImpl editor = myEditor;

    if (!VIM_PLUGIN_LOADED &&
        ENABLED.asBoolean() &&
        editor.getDocument().isWritable() &&
        !editor.isViewer() &&
        canPaintImmediately(editor, c)) {

      paintImmediately(g, editor.getCaretModel().getPrimaryCaret().getOffset(), c);
    }
  }

  private static boolean canPaintImmediately(final EditorImpl editor, final char c) {
    final CaretModel caretModel = editor.getCaretModel();
    final Caret caret = caretModel.getPrimaryCaret();
    final Document document = editor.getDocument();

    return !(editor.getComponent().getParent() instanceof EditorTextField) &&
           document instanceof DocumentImpl &&
           editor.getHighlighter() instanceof LexerEditorHighlighter &&
           !editor.getSelectionModel().hasSelection() &&
           caretModel.getCaretCount() == 1 &&
           !isInVirtualSpace(editor, caret) &&
           !isInsertion(document, caret.getOffset()) &&
           !caret.isAtRtlLocation() &&
           !caret.isAtBidiRunBoundary() &&
           !KEY_CHARS_TO_SKIP.contains(c);
  }

  private static boolean isPluginLoaded(final String id) {
    final PluginId pluginId = PluginId.findId(id);
    if (pluginId == null) return false;
    IdeaPluginDescriptor plugin = PluginManager.getPlugin(pluginId);
    return plugin != null && plugin.isEnabled();
  }

  private static boolean isInVirtualSpace(final Editor editor, final Caret caret) {
    return caret.getLogicalPosition().compareTo(editor.offsetToLogicalPosition(caret.getOffset())) != 0;
  }

  private static boolean isInsertion(final Document document, final int offset) {
    return offset < document.getTextLength() && document.getCharsSequence().charAt(offset) != '\n';
  }

  private void paintImmediately(final Graphics g, final int offset, final char c2) {
    final EditorImpl editor = myEditor;
    final Document document = editor.getDocument();
    final LexerEditorHighlighter highlighter = (LexerEditorHighlighter)myEditor.getHighlighter();

    final EditorSettings settings = editor.getSettings();
    final boolean isBlockCursor = editor.isInsertMode() == settings.isBlockCursor();
    final int lineHeight = editor.getLineHeight();
    final int ascent = editor.getAscent();
    final int topOverhang = editor.myView.getTopOverhang();
    final int bottomOverhang = editor.myView.getBottomOverhang();

    final char c1 = offset == 0 ? ' ' : document.getCharsSequence().charAt(offset - 1);

    final List<TextAttributes> attributes = highlighter.getAttributesForPreviousAndTypedChars(document, offset, c2);
    updateAttributes(editor, offset, attributes);

    final TextAttributes attributes1 = attributes.get(0);
    final TextAttributes attributes2 = attributes.get(1);

    if (!(canRender(attributes1) && canRender(attributes2))) {
      return;
    }

    final int width1 = editor.getFontMetrics(attributes1.getFontType()).charWidth(c1);
    final int width2 = editor.getFontMetrics(attributes2.getFontType()).charWidth(c2);

    final Font font1 = EditorUtil.fontForChar(c1, attributes1.getFontType(), editor).getFont();
    final Font font2 = EditorUtil.fontForChar(c1, attributes2.getFontType(), editor).getFont();

    final Point p2 = editor.offsetToXY(offset, false);

    //noinspection ConstantConditions
    final int caretWidth = isBlockCursor ? editor.getCaretLocations(false)[0].myWidth : JBUI.scale(settings.getLineCursorWidth());
    final int caretShift = isBlockCursor ? 0 : caretWidth == 1 ? 0 : 1;
    final Rectangle caretRectangle = new Rectangle(p2.x + width2 - caretShift, p2.y - topOverhang,
                                                   caretWidth, lineHeight + topOverhang + bottomOverhang + (isBlockCursor ? -1 : 0));

    final Rectangle rectangle1 = new Rectangle(p2.x - width1, p2.y, width1, lineHeight);
    final Rectangle rectangle2 = new Rectangle(p2.x, p2.y, width2 + caretWidth - caretShift, lineHeight);

    final Consumer<Graphics> painter = graphics -> {
      EditorUIUtil.setupAntialiasing(graphics);

      fillRect(graphics, rectangle2, attributes2.getBackgroundColor());
      drawChar(graphics, c2, p2.x, p2.y + ascent, font2, attributes2.getForegroundColor());

      fillRect(graphics, caretRectangle, getCaretColor(editor));

      fillRect(graphics, rectangle1, attributes1.getBackgroundColor());
      drawChar(graphics, c1, p2.x - width1, p2.y + ascent, font1, attributes1.getForegroundColor());
    };

    final Shape originalClip = g.getClip();

    g.setClip(p2.x - caretShift, p2.y, width2 - caretShift + caretWidth + 1, lineHeight);

    if (DOUBLE_BUFFERING.asBoolean()) {
      paintWithDoubleBuffering(g, painter);
    }
    else {
      painter.consume(g);
    }

    g.setClip(originalClip);

    if (PIPELINE_FLUSH.asBoolean()) {
      Toolkit.getDefaultToolkit().sync();
    }

    if (DEBUG.asBoolean()) {
      pause();
    }
  }

  private static boolean canRender(final TextAttributes attributes) {
    return attributes.getEffectType() != EffectType.BOXED || attributes.getEffectColor() == null;
  }

  private void paintWithDoubleBuffering(final Graphics graphics, final Consumer<Graphics> painter) {
    final Rectangle bounds = graphics.getClipBounds();

    createOrUpdateImageBuffer(myEditor.getComponent(), bounds.getSize());

    final Graphics imageGraphics = myImage.getGraphics();
    imageGraphics.translate(-bounds.x, -bounds.y);
    painter.consume(imageGraphics);
    imageGraphics.dispose();

    graphics.drawImage(myImage, bounds.x, bounds.y, null);
  }

  private void createOrUpdateImageBuffer(final JComponent component, final Dimension size) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      if (myImage == null || !isLargeEnough(myImage, size)) {
        myImage = UIUtil.createImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
      }
    }
    else {
      if (myImage == null) {
        myImage = component.createVolatileImage(size.width, size.height);
      }
      else if (!isLargeEnough(myImage, size) ||
               ((VolatileImage)myImage).validate(component.getGraphicsConfiguration()) == VolatileImage.IMAGE_INCOMPATIBLE) {
        myImage.flush();
        myImage = component.createVolatileImage(size.width, size.height);
      }
    }
  }

  private static boolean isLargeEnough(final Image image, final Dimension size) {
    final int width = image.getWidth(null);
    final int height = image.getHeight(null);
    if (width == -1 || height == -1) {
      throw new IllegalArgumentException("Image size is undefined");
    }
    return width >= size.width && height >= size.height;
  }

  private static void fillRect(final Graphics g, final Rectangle r, final Color color) {
    g.setColor(color);
    g.fillRect(r.x, r.y, r.width, r.height);
  }

  private static void drawChar(final Graphics g,
                               final char c,
                               final int x, final int y,
                               final Font font, final Color color) {
    g.setFont(font);
    g.setColor(color);
    g.drawString(String.valueOf(c), x, y);
  }

  private static Color getCaretColor(final Editor editor) {
    final Color caretColor = editor.getColorsScheme().getColor(EditorColors.CARET_COLOR);
    return caretColor == null ? new JBColor(Gray._0, Gray._255) : caretColor;
  }

  private static void updateAttributes(final EditorImpl editor, final int offset, final List<TextAttributes> attributes) {
    final List<RangeHighlighterEx> list1 = new ArrayList<>();
    final List<RangeHighlighterEx> list2 = new ArrayList<>();

    final Processor<RangeHighlighterEx> processor = highlighter -> {
      if (!highlighter.isValid()) return true;

      final boolean isLineHighlighter = highlighter.getTargetArea() == HighlighterTargetArea.LINES_IN_RANGE;

      if (isLineHighlighter || highlighter.getStartOffset() < offset) {
        list1.add(highlighter);
      }

      if (isLineHighlighter || highlighter.getEndOffset() > offset ||
          (highlighter.getEndOffset() == offset && (highlighter.isGreedyToRight()))) {
        list2.add(highlighter);
      }

      return true;
    };

    editor.getFilteredDocumentMarkupModel().processRangeHighlightersOverlappingWith(Math.max(0, offset - 1), offset, processor);
    editor.getMarkupModel().processRangeHighlightersOverlappingWith(Math.max(0, offset - 1), offset, processor);

    updateAttributes(editor, attributes.get(0), list1);
    updateAttributes(editor, attributes.get(1), list2);
  }

  // TODO Unify with com.intellij.openapi.editor.impl.view.IterationState.setAttributes
  private static void updateAttributes(final EditorImpl editor,
                                       final TextAttributes attributes,
                                       final List<RangeHighlighterEx> highlighters) {
    if (highlighters.size() > 1) {
      ContainerUtil.quickSort(highlighters, com.intellij.openapi.editor.impl.view.IterationState.BY_LAYER_THEN_ATTRIBUTES);
    }

    TextAttributes syntax = attributes;
    TextAttributes caretRow = editor.getCaretModel().getTextAttributes();

    final int size = highlighters.size();

    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < size; i++) {
      RangeHighlighterEx highlighter = highlighters.get(i);
      if (highlighter.getTextAttributes() == TextAttributes.ERASE_MARKER) {
        syntax = null;
      }
    }

    final List<TextAttributes> cachedAttributes = new ArrayList<>();

    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < size; i++) {
      RangeHighlighterEx highlighter = highlighters.get(i);

      if (caretRow != null && highlighter.getLayer() < HighlighterLayer.CARET_ROW) {
        cachedAttributes.add(caretRow);
        caretRow = null;
      }

      if (syntax != null && highlighter.getLayer() < HighlighterLayer.SYNTAX) {
        cachedAttributes.add(syntax);
        syntax = null;
      }

      TextAttributes textAttributes = highlighter.getTextAttributes();
      if (textAttributes != null && textAttributes != TextAttributes.ERASE_MARKER) {
        cachedAttributes.add(textAttributes);
      }
    }

    if (caretRow != null) cachedAttributes.add(caretRow);
    if (syntax != null) cachedAttributes.add(syntax);

    Color foreground = null;
    Color background = null;
    Color effect = null;
    EffectType effectType = null;
    int fontType = 0;

    //noinspection ForLoopReplaceableByForEach, Duplicates
    for (int i = 0; i < cachedAttributes.size(); i++) {
      TextAttributes attrs = cachedAttributes.get(i);

      if (foreground == null) {
        foreground = attrs.getForegroundColor();
      }

      if (background == null) {
        background = attrs.getBackgroundColor();
      }

      if (fontType == Font.PLAIN) {
        fontType = attrs.getFontType();
      }

      if (effect == null) {
        effect = attrs.getEffectColor();
        effectType = attrs.getEffectType();
      }
    }

    if (foreground == null) foreground = editor.getForegroundColor();
    if (background == null) background = editor.getBackgroundColor();
    if (effectType == null) effectType = EffectType.BOXED;
    TextAttributes defaultAttributes = editor.getColorsScheme().getAttributes(HighlighterColors.TEXT);
    if (fontType == Font.PLAIN) fontType = defaultAttributes == null ? Font.PLAIN : defaultAttributes.getFontType();

    attributes.setAttributes(foreground, background, effect, null, effectType, fontType);
  }

  private static void pause() {
    try {
      Thread.sleep(DEBUG_PAUSE_DURATION);
    }
    catch (InterruptedException e) {
      // ...
    }
  }
}
