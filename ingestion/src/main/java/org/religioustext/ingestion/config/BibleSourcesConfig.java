// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.ingestion.config;

import org.religioustext.ingestion.model.IngestionRequest;
import org.religioustext.ingestion.model.IngestionRequest.SourceType;
import org.religioustext.ingestion.model.IngestionRequest.SourceFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Loads Bible translation sources from bible-sources.yml.
 * Each entry maps to an IngestionRequest with an API.Bible ID.
 */
@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "")
public class BibleSourcesConfig {

    private static final Logger log = LoggerFactory.getLogger(BibleSourcesConfig.class);

    private List<TranslationDef> translations;

    public List<TranslationDef> getTranslations() {
        return translations;
    }

    public void setTranslations(final List<TranslationDef> theTranslations) {
        this.translations = theTranslations;
    }

    public List<IngestionRequest> toIngestionRequests() {
        if (translations == null) return List.of();
        return translations.stream()
            .map(this::toRequest)
            .toList();
    }

    public IngestionRequest findByAbbreviation(final String anAbbreviation) {
        if (translations == null) return null;
        return translations.stream()
            .filter(t -> t.getAbbreviation().equalsIgnoreCase(anAbbreviation))
            .findFirst()
            .map(this::toRequest)
            .orElse(null);
    }

    private IngestionRequest toRequest(final TranslationDef aDef) {
        final String sourceUrl = aDef.isLocal()
            ? "local:" + aDef.getLocalCode() + ":" + (aDef.getApiBibleId() != null ? aDef.getApiBibleId() : "")
            : "https://rest.api.bible/v1/bibles/" + aDef.getApiBibleId();
        return IngestionRequest.builder()
            .sourceUrl(sourceUrl)
            .sourceType(SourceType.BIBLE)
            .sourceFormat(SourceFormat.AUTO_DETECT)
            .translation(aDef.getTranslation())
            .abbreviation(aDef.getAbbreviation())
            .bcp47Language(aDef.getBcp47Language())
            .iso639_3(aDef.getIso639_3())
            .direction(aDef.getDirection() != null ? aDef.getDirection() : "ltr")
            .year(aDef.getYear())
            .license(aDef.getLicense() != null ? aDef.getLicense() : "Public Domain")
            .region(aDef.getRegion())
            .attributionUrl(aDef.getSourceUrl())
            .build();
    }

    public static class TranslationDef {
        private String  id;
        private String  translation;
        private String  abbreviation;
        private String  apiBibleId;
        private String  localCode;
        private String  bcp47Language;
        private String  iso639_3;
        private String  direction;
        private Integer year;
        private String  license;
        private String  region;
        private String  sourceUrl;

        public String  getId()            { return id; }
        public String  getTranslation()   { return translation; }
        public String  getAbbreviation()  { return abbreviation; }
        public String  getApiBibleId()    { return apiBibleId; }
        public String  getLocalCode()     { return localCode; }
        public boolean isLocal()          { return localCode != null && !localCode.isBlank(); }
        public String  getBcp47Language() { return bcp47Language; }
        public String  getIso639_3()      { return iso639_3; }
        public String  getDirection()     { return direction; }
        public Integer getYear()          { return year; }
        public String  getLicense()       { return license; }
        public String  getRegion()        { return region; }
        public String  getSourceUrl()     { return sourceUrl; }

        public void setId(final String anId)                     { this.id = anId; }
        public void setTranslation(final String aTranslation)    { this.translation = aTranslation; }
        public void setAbbreviation(final String anAbbreviation) { this.abbreviation = anAbbreviation; }
        public void setApiBibleId(final String anApiBibleId)     { this.apiBibleId = anApiBibleId; }
        public void setLocalCode(final String aLocalCode)        { this.localCode = aLocalCode; }
        public void setBcp47Language(final String aBcp47Language){ this.bcp47Language = aBcp47Language; }
        public void setIso639_3(final String anIso639_3)         { this.iso639_3 = anIso639_3; }
        public void setDirection(final String aDirection)        { this.direction = aDirection; }
        public void setYear(final Integer aYear)                 { this.year = aYear; }
        public void setLicense(final String aLicense)            { this.license = aLicense; }
        public void setRegion(final String aRegion)              { this.region = aRegion; }
        public void setSourceUrl(final String aSourceUrl)        { this.sourceUrl = aSourceUrl; }
    }
}
