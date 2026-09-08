package org.religioustext.app.i18n;

import com.vaadin.flow.i18n.I18NProvider;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Vaadin {@link I18NProvider} backed by Java {@link ResourceBundle}s.
 *
 * Bundles live at {@code src/main/resources/i18n/translations*.properties}.
 * {@code translations.properties} is the English default and the fallback for
 * any key missing from a locale-specific bundle (ResourceBundle's parent chain
 * resolves missing keys up to the base bundle automatically). Properties files
 * are read as UTF-8 (Java 9+), so Arabic / Cyrillic / CJK values are stored
 * directly without \\uXXXX escaping.
 *
 * Components call {@code getTranslation("key")}; Vaadin routes that here with the
 * active UI locale. Unknown keys return the key itself, which makes missing
 * translations obvious in the UI rather than throwing.
 */
@Component
public class TranslationProvider implements I18NProvider {

    private static final String BUNDLE = "i18n.translations";

    @Override
    public java.util.List<Locale> getProvidedLocales() {
        return LocaleUtil.LOCALES;
    }

    @Override
    public String getTranslation(final String key, final Locale locale, final Object... params) {
        if (key == null) {
            return "";
        }
        final ResourceBundle bundle;
        try {
            bundle = ResourceBundle.getBundle(BUNDLE, locale != null ? locale : LocaleUtil.EN);
        } catch (final MissingResourceException e) {
            return key;
        }
        if (!bundle.containsKey(key)) {
            return key;
        }
        final String value = bundle.getString(key);
        if (params != null && params.length > 0) {
            return new MessageFormat(value, locale).format(params);
        }
        return value;
    }
}
