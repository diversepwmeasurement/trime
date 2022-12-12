/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.osfans.trime.data.theme;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.NinePatch;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.NinePatchDrawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;
import com.blankj.utilcode.util.FileUtils;
import com.osfans.trime.core.Rime;
import com.osfans.trime.data.AppPrefs;
import com.osfans.trime.data.DataManager;
import com.osfans.trime.ime.keyboard.Key;
import com.osfans.trime.ime.keyboard.Sound;
import com.osfans.trime.util.CollectionUtils;
import com.osfans.trime.util.DimensionsKt;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kotlin.Pair;
import kotlin.collections.MapsKt;
import timber.log.Timber;

/** 解析 YAML 配置文件 */
public class Config {
  private static Config self = null;

  private static final AppPrefs appPrefs = AppPrefs.defaultInstance();

  private static final String sharedDataDir = appPrefs.getProfile().getSharedDataDir();
  private static final String userDataDir = appPrefs.getProfile().getUserDataDir();

  public static Config get() {
    if (self == null) self = new Config();
    return self;
  }

  private String soundPackageName, currentSound;
  private static final String defaultThemeName = "trime";
  private String currentColorSchemeId;

  private Map<String, Object> generalStyle;
  private Map<String, String> fallbackColors;
  private Map<String, Map<String, String>> presetColorSchemes;
  private Map<String, Object> presetKeyboards;
  private Map<String, Object> liquidKeyboard;

  public Style style;
  public Liquid liquid;
  public Keyboards keyboards;

  public Config() {
    self = this;
    ThemeManager.init();
    soundPackageName = appPrefs.getKeyboard().getSoundPackage();

    Timber.d("Syncing asset data ...");
    DataManager.sync();
    Rime.get(!DataManager.INSTANCE.getSharedDataDir().exists());

    init();

    Timber.d("Setting sound from color ...");
    setSoundFromColor();

    Timber.d("Initialization finished");
  }

  public String getSoundPackage() {
    return soundPackageName;
  }

  // 设置音效包
  public void setSoundPackage(String name) {
    soundPackageName = name;
    applySoundPackage(name);
    appPrefs.getKeyboard().setSoundPackage(soundPackageName);
  }

  // 应用音效包
  private boolean applySoundPackage(@NonNull String name) {
    final String src = userDataDir + "/sound/" + name + ".sound.yaml";
    final String dest = userDataDir + "/build/" + name + ".sound.yaml";
    boolean result = FileUtils.copy(src, dest);
    if (result) {
      Sound.get(name);
      currentSound = name;
    }
    return result;
  }

  // 配色指定音效时自动切换音效效果（不会自动修改设置）。
  public void setSoundFromColor() {
    final Map<String, String> m = presetColorSchemes.get(currentColorSchemeId);
    assert m != null;
    if (m.containsKey("sound")) {
      String sound = m.get("sound");
      if (!Objects.equals(sound, currentSound)) {
        if (applySoundPackage(sound)) {
          return;
        }
      }
    }

    if (!Objects.equals(currentSound, soundPackageName)) {
      setSoundPackage(soundPackageName);
    }
  }

  public void init() {
    Timber.i("Initializing theme, currentThemeName=%s ...", ThemeManager.getActiveTheme());
    try {
      final String fullThemeFileName = ThemeManager.getActiveTheme() + ".yaml";
      final File themeFile = new File(Rime.get_user_data_dir(), "build/" + fullThemeFileName);
      if (themeFile.exists()) {
        Timber.i("Deployed file exists, skipping deployment ...");
      } else {
        Timber.i("The theme has not been deployed yet, deploying ...");
        Rime.deploy_config_file(fullThemeFileName, "config_version");
      }

      Timber.d("Fetching global theme config map ...");
      long start = System.currentTimeMillis();
      Map<String, Object> fullThemeConfigMap;
      if ((fullThemeConfigMap = Rime.getRimeConfigMap(ThemeManager.getActiveTheme(), "")) == null) {
        fullThemeConfigMap = Rime.getRimeConfigMap(defaultThemeName, "");
      }

      Objects.requireNonNull(fullThemeConfigMap, "The theme file cannot be empty!");
      Timber.d("Fetching done");

      generalStyle = (Map<String, Object>) fullThemeConfigMap.get("style");
      fallbackColors = (Map<String, String>) fullThemeConfigMap.get("fallback_colors");
      Key.presetKeys = (Map<String, Map<String, Object>>) fullThemeConfigMap.get("preset_keys");
      presetColorSchemes =
          (Map<String, Map<String, String>>) fullThemeConfigMap.get("preset_color_schemes");
      presetKeyboards = (Map<String, Object>) fullThemeConfigMap.get("preset_keyboards");
      liquidKeyboard = (Map<String, Object>) fullThemeConfigMap.get("liquid_keyboard");
      style = new Style(this);
      liquid = new Liquid(this);
      keyboards = new Keyboards(this);
      long end = System.currentTimeMillis();
      Timber.d("Setting up all theme config map takes %s ms", end - start);
      initCurrentColors();
      Timber.i("The theme is initialized");
      long initEnd = System.currentTimeMillis();
      Timber.d("Initializing cache takes %s ms", initEnd - end);
    } catch (Exception e) {
      Timber.e(e, "Failed to parse the theme!");
      if (!ThemeManager.getActiveTheme().equals(defaultThemeName)) {
        ThemeManager.switchTheme(defaultThemeName);
        init();
      }
    }
  }

  public static class Style {
    private final Config theme;

    public Style(@NonNull final Config theme) {
      this.theme = theme;
    }

    public String getString(@NonNull String key) {
      return CollectionUtils.obtainString(theme.generalStyle, key, "");
    }

    public int getInt(@NonNull String key) {
      return CollectionUtils.obtainInt(theme.generalStyle, key, 0);
    }

    public float getFloat(@NonNull String key) {
      return CollectionUtils.obtainFloat(theme.generalStyle, key, 0f);
    }

    public boolean getBoolean(@NonNull String key) {
      return CollectionUtils.obtainBoolean(theme.generalStyle, key, false);
    }

    public Object getObject(@NonNull String key) {
      return CollectionUtils.obtainValue(theme.generalStyle, key);
    }
  }

  public static class Liquid {
    private final Config theme;

    public Liquid(@NonNull final Config theme) {
      this.theme = theme;
    }

    public Object getObject(@NonNull String key) {
      return CollectionUtils.obtainValue(theme.liquidKeyboard, key);
    }

    public int getInt(@NonNull String key) {
      return CollectionUtils.obtainInt(theme.liquidKeyboard, key, 0);
    }

    public float getFloat(@NonNull String key) {
      return CollectionUtils.obtainFloat(theme.liquidKeyboard, key, theme.style.getFloat(key));
    }
  }

  public static class Keyboards {
    private final Config theme;

    public Keyboards(@NonNull final Config theme) {
      this.theme = theme;
    }

    public Object getObject(@NonNull String key) {
      return CollectionUtils.obtainValue(theme.presetKeyboards, key);
    }

    public String remapKeyboardId(@NonNull String name) {
      final String remapped;
      if (".default".equals(name)) {
        final String currentSchemaId = Rime.get_current_schema();
        final String shortSchemaId = currentSchemaId.split("_")[0];
        if (theme.presetKeyboards.containsKey(shortSchemaId)) {
          return shortSchemaId;
        } else {
          final String alphabet =
              (String) Rime.getRimeSchemaValue(currentSchemaId, "speller/alphabet");
          final String twentySix = "qwerty";
          if (theme.presetKeyboards.containsKey(alphabet)) {
            return alphabet;
          } else {
            if (alphabet != null && (alphabet.contains(",") || alphabet.contains(";"))) {
              remapped = twentySix + "_";
            } else if (alphabet != null && (alphabet.contains("0") || alphabet.contains("1"))) {
              remapped = twentySix + "0";
            } else {
              remapped = twentySix;
            }
          }
        }
      } else {
        remapped = name;
      }
      if (!theme.presetKeyboards.containsKey(remapped)) {
        Timber.w("Cannot find keyboard definition %s, fallback ...", remapped);
        final Map<String, Object> defaultMap =
            (Map<String, Object>) theme.presetKeyboards.get("default");
        if (defaultMap == null)
          throw new IllegalStateException("The default keyboard definition is missing!");
        if (defaultMap.containsKey("import_preset")) {
          final String v;
          return ((v = (String) defaultMap.get("import_preset")) != null) ? v : "default";
        }
      }
      return remapped;
    }
  }

  public boolean hasKey(String s) {
    return style.getObject(s) != null;
  }

  public void destroy() {
    if (style != null) style = null;
    if (liquid != null) liquid = null;
    if (keyboards != null) keyboards = null;
    self = null;
  }

  private int[] keyboardPadding;

  public int[] getKeyboardPadding() {
    return keyboardPadding;
  }

  public int[] getKeyboardPadding(boolean land_mode) {
    Timber.i("update KeyboardPadding: getKeyboardPadding(boolean land_mode) ");
    return getKeyboardPadding(one_hand_mode, land_mode);
  }

  private int one_hand_mode;

  public int[] getKeyboardPadding(int one_hand_mode, boolean land_mode) {
    keyboardPadding = new int[3];
    this.one_hand_mode = one_hand_mode;
    if (land_mode) {
      keyboardPadding[0] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_land"));
      keyboardPadding[1] = keyboardPadding[0];
      keyboardPadding[2] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_land_bottom"));
    } else {
      switch (one_hand_mode) {
        case 0:
          // 普通键盘 预留，目前未实装
          keyboardPadding[0] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding"));
          keyboardPadding[1] = keyboardPadding[0];
          keyboardPadding[2] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_bottom"));
          break;
        case 1:
          // 左手键盘
          keyboardPadding[0] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_left"));
          keyboardPadding[1] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_right"));
          keyboardPadding[2] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_bottom"));
          break;
        case 2:
          // 右手键盘
          keyboardPadding[1] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_left"));
          keyboardPadding[0] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_right"));
          keyboardPadding[2] = (int) DimensionsKt.dp2px(style.getFloat("keyboard_padding_bottom"));
          break;
      }
    }
    Timber.d(
        "update KeyboardPadding: %s %s %s one_hand_mode=%s",
        keyboardPadding[0], keyboardPadding[1], keyboardPadding[2], one_hand_mode);
    return keyboardPadding;
  }

  public static Integer getColor(@NonNull Map<?, ?> m, String k) {
    Integer color = null;
    if (m.containsKey(k)) {
      Object o = m.get(k);
      color = parseColor(o);
      if (color == null) color = get().getCurrentColor(o.toString());
    }
    return color;
  }

  // API 2.0
  public Integer getColor(String key) {
    Object o;
    if (currentColors.containsKey(key)) {
      o = currentColors.get(key);
      if (o instanceof Integer) return (Integer) o;
    }
    o = getColorValue(key);
    if (o == null) {
      o = (Objects.requireNonNull(presetColorSchemes.get(currentColorSchemeId))).get(key);
    }
    return parseColor(o);
  }

  // API 2.0
  public Drawable getDrawable(@NonNull Map<?, ?> m, String k) {
    if (m.containsKey(k)) {
      final Object o = m.get(k);
      //      Timber.d("getColorDrawable()" + k + " " + o);
      return drawableObject(o);
    }
    return null;
  }

  //  获取当前配色方案的key的value，或者从fallback获取值。
  @Nullable
  private String getColorValue(String key) {
    final Map<String, String> map = presetColorSchemes.get(currentColorSchemeId);
    if (map == null) return null;
    String value;
    String newKey = key;
    int limit = fallbackColors.size() * 2;
    for (int i = 0; i < limit; i++) {
      if ((value = map.get(newKey)) != null || !fallbackColors.containsKey(newKey)) return value;
      newKey = fallbackColors.get(newKey);
    }
    return null;
  }

  /**
   * 获取配色方案名<br>
   * 优先级：设置>color_scheme>default <br>
   * 避免直接读取 default
   *
   * @return java.lang.String 首个已配置的主题方案名
   */
  private String getColorSchemeName() {
    String schemeId = appPrefs.getThemeAndColor().getSelectedColor();
    if (!presetColorSchemes.containsKey(schemeId))
      schemeId = style.getString("color_scheme"); // 主題中指定的配色
    if (!presetColorSchemes.containsKey(schemeId)) schemeId = "default"; // 主題中的default配色
    Map<String, String> colorMap = presetColorSchemes.get(schemeId);
    if (colorMap.containsKey("dark_scheme") || colorMap.containsKey("light_scheme"))
      hasDarkLight = true;
    return schemeId;
  }

  private boolean hasDarkLight;

  public boolean hasDarkLight() {
    return hasDarkLight;
  }

  /**
   * 获取暗黑模式/明亮模式下配色方案的名称
   *
   * @param darkMode 是否暗黑模式
   * @return 配色方案名称
   */
  private String getColorSchemeName(boolean darkMode) {
    String scheme = appPrefs.getThemeAndColor().getSelectedColor();
    if (!presetColorSchemes.containsKey(scheme))
      scheme = style.getString("color_scheme"); // 主題中指定的配色
    if (!presetColorSchemes.containsKey(scheme)) scheme = "default"; // 主題中的default配色
    Map<String, String> colorMap = presetColorSchemes.get(scheme);
    if (darkMode) {
      if (colorMap.containsKey("dark_scheme")) {
        return colorMap.get("dark_scheme");
      }
    } else {
      if (colorMap.containsKey("light_scheme")) {
        return colorMap.get("light_scheme");
      }
    }
    return scheme;
  }

  // API 2.0
  private static Integer parseColor(Object object) {
    if (object == null) return null;
    if (object instanceof Integer) {
      return (Integer) object;
    }
    if (object instanceof Long) {
      Long o = (Long) object;
      // 这个方法可以把超出Integer.MAX_VALUE的值处理为负数int
      return o.intValue();
    }
    return parseColor(object.toString());
  }

  @Nullable
  private static Integer parseColor(@NonNull String s) {
    if (s.contains(".")) return null; // picture name
    final String hex = s.startsWith("#") ? s.replace("#", "0x") : s;
    try {
      final String completed;
      if (hex.startsWith("0x") || hex.startsWith("0X")) {
        if (hex.length() == 3 || hex.length() == 4) {
          completed = String.format("#%02x000000", Long.decode(hex)); // 0xA -> #AA000000
        } else if (hex.length() < 8) { // 0xGBB -> #RRGGBB
          completed = String.format("#%06x", Long.decode(hex));
        } else if (hex.length() == 9) { // 0xARRGGBB -> #AARRGGBB
          completed = "#0" + hex.substring(2);
        } else {
          completed = "#" + hex.substring(2); // 0xAARRGGBB -> #AARRGGBB, 0xRRGGBB -> #RRGGBB
        }
      } else {
        completed = hex; // red, green, blue ...
      }
      return Color.parseColor(completed);
    } catch (Exception e) {
      Timber.w(e, "Error on parsing color: %s", s);
      return null;
    }
  }

  @NonNull
  private String joinToFullImagePath(String value) {
    File imgSrc;
    if ((imgSrc =
            new File(
                Rime.get_user_data_dir(),
                "backgrounds/" + style.getString("background_folder") + value))
        .exists()) {
      return imgSrc.getPath();
    } else if ((imgSrc = new File(Rime.get_user_data_dir(), "backgrounds/" + value)).exists()) {
      return imgSrc.getPath();
    }
    return "";
  }

  public Integer getCurrentColor(String key) {
    Object o = getColorValue(key);
    return parseColor(o);
  }

  @NonNull
  public List<Pair<String, String>> getPresetColorSchemes() {
    if (presetColorSchemes == null) return new ArrayList<>();
    return MapsKt.map(
        presetColorSchemes,
        entry -> new Pair<>(entry.getKey(), Objects.requireNonNull(entry.getValue().get("name"))));
  }

  //  返回drawable。参数可以是颜色或者图片。如果参数缺失，返回null
  private Drawable drawableObject(Object o) {
    if (o == null) return null;
    String name = o.toString();
    Integer color = parseColor(o);
    if (color == null) {
      if (currentColors.containsKey(name)) {
        o = currentColors.get(name);
        color = parseColor(o);
      }
    }
    if (color != null) {
      final GradientDrawable gd = new GradientDrawable();
      gd.setColor(color);
      return gd;
    }
    return drawableBitmapObject(name);
  }

  //  返回图片的drawable。如果参数缺失、非图片，返回null
  private Drawable drawableBitmapObject(Object o) {
    if (o == null) return null;
    if (o instanceof String) {
      final String value = (String) o;
      File imgSrc = new File(joinToFullImagePath(value));

      if (!imgSrc.exists()) {
        if (currentColors.containsKey(value)) {
          final Object v = currentColors.get(value);
          if (v instanceof String) imgSrc = new File((String) v);
        }
      }

      if (imgSrc.exists()) {
        final String path = imgSrc.getPath();
        return getBitmapDrawable(path);
      }
    }
    return null;
  }

  public Drawable getColorDrawable(String key) {
    final Object o = getColorValue(key);
    return drawableObject(o);
  }

  // 获取当前色彩 Config 2.0
  public Integer getCurrentColor_(String key) {
    Object o = currentColors.get(key);
    return (Integer) o;
  }

  // 获取当前背景图路径 Config 2.0
  public String getCurrentImage(String key) {
    Object o = currentColors.get(key);
    if (o instanceof String) return (String) o;
    return "";
  }

  //  返回drawable。  Config 2.0
  //  参数可以是颜色或者图片。如果参数缺失，返回null
  public Drawable getDrawable_(String key) {
    if (key == null) return null;
    Object o = currentColors.get(key);
    if (o instanceof Integer) {
      Integer color = (Integer) o;
      final GradientDrawable gd = new GradientDrawable();
      gd.setColor(color);
      return gd;
    } else if (o instanceof String) return getDrawableBitmap_(key);

    return null;
  }

  //  返回图片或背景的drawable,支持null参数。 Config 2.0
  public Drawable getDrawable(
      String key, String borderKey, String borderColorKey, String roundCornerKey, String alphaKey) {
    if (key == null) return null;
    Drawable drawable = getDrawableBitmap_(key);
    if (drawable != null) {
      if (alphaKey != null) {
        if (hasKey(alphaKey)) {
          int alpha = MathUtils.clamp(style.getInt(alphaKey), 0, 255);
          drawable.setAlpha(alpha);
        }
      }
      return drawable;
    }

    GradientDrawable gd = new GradientDrawable();
    Object o = currentColors.get(key);
    if (!(o instanceof Integer)) return null;
    gd.setColor((int) o);

    if (roundCornerKey != null) gd.setCornerRadius(style.getFloat(roundCornerKey));

    if (borderColorKey != null && borderKey != null) {
      int border = (int) DimensionsKt.dp2px(style.getFloat(borderKey));
      Object borderColor = currentColors.get(borderColorKey);
      if (borderColor instanceof Integer && border > 0) {
        gd.setStroke(border, getCurrentColor_(borderColorKey));
      }
    }

    if (alphaKey != null) {
      if (hasKey(alphaKey)) {
        int alpha = MathUtils.clamp(style.getInt(alphaKey), 0, 255);
        gd.setAlpha(alpha);
      }
    }

    return gd;
  }

  //  返回图片的drawable。 Config 2.0
  //  如果参数缺失、非图片，返回null. 在genCurrentColors()中已经验证存在文件，因此不需要重新验证。
  public Drawable getDrawableBitmap_(String key) {
    if (key == null) return null;

    Object o = currentColors.get(key);
    if (o instanceof String) {
      String path = (String) o;
      return getBitmapDrawable(path);
    }
    return null;
  }

  private Drawable getBitmapDrawable(@NonNull String path) {
    if (path.contains(".9.png")) {
      final Bitmap bitmap = BitmapFactory.decodeFile(path);
      final byte[] chunk = bitmap.getNinePatchChunk();
      if (NinePatch.isNinePatchChunk(chunk))
        return new NinePatchDrawable(Resources.getSystem(), bitmap, chunk, new Rect(), null);
    }
    return Drawable.createFromPath(path);
  }

  // 遍历当前配色方案的值、fallback的值，从而获得当前方案的全部配色Map
  private final Map<String, Object> currentColors = new HashMap<>();
  // 初始化当前配色 Config 2.0
  public void initCurrentColors() {
    currentColorSchemeId = getColorSchemeName();
    Timber.i("Caching color values (currentColorSchemeId=%s) ...", currentColorSchemeId);
    cacheColorValues();
  }

  // 当切换暗黑模式时，刷新键盘配色方案
  public void initCurrentColors(boolean darkMode) {
    currentColorSchemeId = getColorSchemeName(darkMode);
    Timber.i(
        "Caching color values (currentColorSchemeId=%s, isDarkMode=%s) ...",
        currentColorSchemeId, darkMode);
    cacheColorValues();
  }

  private void cacheColorValues() {
    currentColors.clear();
    final Map<String, String> colorMap = presetColorSchemes.get(currentColorSchemeId);
    if (colorMap == null) {
      Timber.w("Color scheme id not found: %s", currentColorSchemeId);
      return;
    }
    appPrefs.getThemeAndColor().setSelectedColor(currentColorSchemeId);

    for (Map.Entry<String, String> entry : colorMap.entrySet()) {
      final String key = entry.getKey();
      if (key.equals("name") || key.equals("author")) continue;
      Object value = parseColorValue(entry.getValue());
      if (value != null) currentColors.put(key, value);
    }

    for (Map.Entry<String, String> entry : fallbackColors.entrySet()) {
      final String key = entry.getKey();
      if (!currentColors.containsKey(key)) {
        final Object value = parseColorValue(getColorValue(key));
        if (value != null) currentColors.put(key, value);
      }
    }
  }

  // 获取参数的真实value，Config 2.0
  // 如果是色彩返回int，如果是背景图返回path string，如果处理失败返回null
  private Object parseColorValue(String value) {
    if (value == null) return null;
    if (value.matches(".*[.\\\\/].*")) {
      return joinToFullImagePath(value);
    } else {
      try {
        return parseColor(value);
      } catch (Exception e) {
        Timber.e(e, "Unknown color value: %s", value);
      }
    }
    return null;
  }
}