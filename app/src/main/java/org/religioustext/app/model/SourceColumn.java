package org.religioustext.app.model;

/**
 * Represents one column in the multi-column reader view.
 * Each column displays one translation/source independently
 * but all columns sync to the same current reference point.
 * Display options are per-column so one column can show CAPS
 * while another shows normal prose.
 */
public final class SourceColumn {

    private String         sourceId;
    private String         translation;
    private String         abbreviation;
    private String         direction;
    private String         license;
    private String         attributionUrl;
    private final DisplayOptions displayOptions;
    private boolean        synced = true;

    public SourceColumn(final DisplayOptions aDisplayOptions) {
        this.displayOptions = aDisplayOptions;
    }

    public SourceColumn(
         final String         aSourceId
        , final String         aTranslation
        , final String         anAbbreviation
        , final String         aDirection
        , final DisplayOptions aDisplayOptions) {

        this.sourceId       = aSourceId;
        this.translation    = aTranslation;
        this.abbreviation   = anAbbreviation;
        this.direction      = aDirection;
        this.displayOptions = aDisplayOptions;
    }

    public String         getSourceId()        { return sourceId; }
    public String         getTranslation()     { return translation; }
    public String         getAbbreviation()    { return abbreviation; }
    public String         getDirection()       { return direction; }
    public String         getLicense()         { return license; }
    public String         getAttributionUrl()  { return attributionUrl; }
    public DisplayOptions getDisplayOptions()  { return displayOptions; }
    public boolean        isSynced()           { return synced; }

    public void setSource(final String aSourceId, final String aTranslation,
                          final String anAbbreviation, final String aDirection,
                          final String aLicense, final String anAttributionUrl) {
        this.sourceId       = aSourceId;
        this.translation    = aTranslation;
        this.abbreviation   = anAbbreviation;
        this.direction      = aDirection;
        this.license        = aLicense;
        this.attributionUrl = anAttributionUrl;
    }

    // Keep old signature for compatibility
    public void setSource(final String aSourceId, final String aTranslation,
                          final String anAbbreviation, final String aDirection) {
        setSource(aSourceId, aTranslation, anAbbreviation, aDirection, null, null);
    }

    public void toggleSync() { this.synced = !this.synced; }

    public boolean isRtl() {
        return "rtl".equalsIgnoreCase(direction);
    }
}
