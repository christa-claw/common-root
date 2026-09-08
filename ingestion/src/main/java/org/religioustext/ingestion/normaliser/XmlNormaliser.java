package org.religioustext.ingestion.normaliser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.StringWriter;

/**
 * Validates a parsed DOM Document against the canonical XSD schema
 * and serialises it to a UTF-8 XML string ready for storage in BaseX.
 */
@Component
public class XmlNormaliser {

    private static final Logger log = LoggerFactory.getLogger(XmlNormaliser.class);

    private static final String SCHEMA_CLASSPATH = "/schema/religious-text.xsd";

    /**
     * Validates the document against the canonical schema.
     * Throws IllegalArgumentException if validation fails.
     */
    public void validate(final Document aDocument) throws Exception {
        log.info("Validating document against canonical schema");

        final SchemaFactory factory =
            SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
        final java.io.InputStream schemaStream =
            getClass().getResourceAsStream(SCHEMA_CLASSPATH);
        if (schemaStream == null) {
            throw new IllegalStateException("Schema not found: " + SCHEMA_CLASSPATH);
        }
        final Schema schema = factory.newSchema(
            new javax.xml.transform.stream.StreamSource(schemaStream));
        final Validator validator = schema.newValidator();
        validator.validate(new DOMSource(aDocument));

        log.info("Document validation passed");
    }

    /**
     * Serialises a DOM Document to a UTF-8 XML string.
     */
    public String serialise(final Document aDocument) throws Exception {
        final TransformerFactory factory     = TransformerFactory.newInstance();
        final Transformer        transformer = factory.newTransformer();

        transformer.setOutputProperty(OutputKeys.ENCODING,              "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT,                "yes");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,  "no");
        transformer.setOutputProperty(
             "{http://xml.apache.org/xslt}indent-amount"
            , "2");

        final StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(aDocument), new StreamResult(writer));

        // Trim any leading whitespace that would appear before the XML declaration
        return writer.toString().trim();
    }

    /**
     * Validates then serialises in one step.
     */
    public String validateAndSerialise(final Document aDocument) throws Exception {
        validate(aDocument);
        return serialise(aDocument);
    }
}
