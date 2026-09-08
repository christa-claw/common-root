// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.ingestion.model;

/**
 * Describes a single ingestion request — what to fetch,
 * what format it is, and what metadata to attach.
 */
public final class IngestionRequest {

    public enum SourceType  { BIBLE, QURAN, TORAH, COMMENTARY }
    public enum SourceFormat { OSIS, TEI, ZEFANIA, PLAIN, AUTO_DETECT }

    private final String       sourceUrl;
    private final SourceType   sourceType;
    private final SourceFormat sourceFormat;
    private final String       translation;
    private final String       abbreviation;
    private final String       bcp47Language;
    private final String       iso639_3;
    private final String       direction;
    private final Integer      year;
    private final String       license;
    private final String       region;
    private final String       attributionUrl;

    private IngestionRequest(final Builder aBuilder) {
        this.sourceUrl     = aBuilder.sourceUrl;
        this.sourceType    = aBuilder.sourceType;
        this.sourceFormat  = aBuilder.sourceFormat;
        this.translation   = aBuilder.translation;
        this.abbreviation  = aBuilder.abbreviation;
        this.bcp47Language = aBuilder.bcp47Language;
        this.iso639_3      = aBuilder.iso639_3;
        this.direction     = aBuilder.direction;
        this.year          = aBuilder.year;
        this.license       = aBuilder.license;
        this.region        = aBuilder.region;
        this.attributionUrl = aBuilder.attributionUrl;
    }

    public String       getSourceUrl()     { return sourceUrl; }
    public SourceType   getSourceType()    { return sourceType; }
    public SourceFormat getSourceFormat()  { return sourceFormat; }
    public String       getTranslation()   { return translation; }
    public String       getAbbreviation()  { return abbreviation; }
    public String       getBcp47Language() { return bcp47Language; }
    public String       getIso639_3()      { return iso639_3; }
    public String       getDirection()     { return direction; }
    public Integer      getYear()          { return year; }
    public String       getLicense()       { return license; }
    public String       getRegion()        { return region; }
    public String       getAttributionUrl(){ return attributionUrl; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private String       sourceUrl;
        private SourceType   sourceType;
        private SourceFormat sourceFormat  = SourceFormat.AUTO_DETECT;
        private String       translation;
        private String       abbreviation;
        private String       bcp47Language = "en";
        private String       iso639_3      = "eng";
        private String       direction     = "ltr";
        private Integer      year;
        private String       license;
        private String       region;
        private String       attributionUrl;

        public Builder sourceUrl(final String aSourceUrl) {
            this.sourceUrl = aSourceUrl;
            return this;
        }

        public Builder sourceType(final SourceType aSourceType) {
            this.sourceType = aSourceType;
            return this;
        }

        public Builder sourceFormat(final SourceFormat aSourceFormat) {
            this.sourceFormat = aSourceFormat;
            return this;
        }

        public Builder translation(final String aTranslation) {
            this.translation = aTranslation;
            return this;
        }

        public Builder abbreviation(final String anAbbreviation) {
            this.abbreviation = anAbbreviation;
            return this;
        }

        public Builder bcp47Language(final String aBcp47Language) {
            this.bcp47Language = aBcp47Language;
            return this;
        }

        public Builder iso639_3(final String anIso639_3) {
            this.iso639_3 = anIso639_3;
            return this;
        }

        public Builder direction(final String aDirection) {
            this.direction = aDirection;
            return this;
        }

        public Builder year(final Integer aYear) {
            this.year = aYear;
            return this;
        }

        public Builder license(final String aLicense) {
            this.license = aLicense;
            return this;
        }

        public Builder region(final String aRegion) {
            this.region = aRegion;
            return this;
        }

        public Builder attributionUrl(final String anAttributionUrl) {
            this.attributionUrl = anAttributionUrl;
            return this;
        }

        public IngestionRequest build() {
            return new IngestionRequest(this);
        }
    }
}
