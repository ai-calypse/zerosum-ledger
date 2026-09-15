// decision: D01-2 — docs/step_01_domain_contracts.md#decisions-and-outputs
//
// Generates libs/money/src/main/resources/dev/zerosum/money/iso4217-minor-units.csv from ISO 4217 List One.
// Run by a developer, never by the build or CI:
//
//   java libs/money/scripts/GenerateMinorUnitTable.java <list-one.xml> <output.csv>
//
// Output is deterministic (sorted, no timestamps, LF), so regenerating from a file whose SHA-256 matches the
// table header reproduces the checked-in table byte for byte.

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class GenerateMinorUnitTable {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java GenerateMinorUnitTable.java <list-one.xml> <output.csv>");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        byte[] bytes = Files.readAllBytes(input);

        Element root;
        try {
            root = parseSecurely(bytes).getDocumentElement();
        } catch (org.xml.sax.SAXException e) {
            // Includes a rejected DOCTYPE or external entity: the file is refused, nothing is written.
            require(false, "cannot parse " + input + ": " + e.getMessage());
            return;
        }
        require("ISO_4217".equals(root.getTagName()), "root element ISO_4217 not found (found " + root.getTagName() + ")");
        String published = root.getAttribute("Pblshd");
        require(!published.isEmpty(), "attribute Pblshd missing on element ISO_4217");

        NodeList entries = root.getElementsByTagName("CcyNtry");
        require(entries.getLength() > 0, "no CcyNtry elements found");

        Map<String, Integer> digits = new TreeMap<>();
        TreeSet<String> nonNumeric = new TreeSet<>();
        List<String> withoutCode = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String country = text(entry, "CtryNm");
            String code = text(entry, "Ccy");
            String minor = text(entry, "CcyMnrUnts");
            if (code == null) {
                withoutCode.add(country == null ? "(no country name)" : country);
                continue;
            }
            require(code.matches("[A-Z]{3}"), "element Ccy has invalid code '" + code + "' for " + country);
            if (minor == null || !minor.matches("[0-9]")) {
                nonNumeric.add(code);
                continue;
            }
            int value = Integer.parseInt(minor);
            Integer previous = digits.putIfAbsent(code, value);
            require(previous == null || previous == value,
                    "conflicting CcyMnrUnts for " + code + ": " + previous + " and " + value);
        }
        nonNumeric.removeAll(digits.keySet());

        StringBuilder out = new StringBuilder();
        out.append("# ISO 4217 minor-unit digits per alphabetic currency code, generated from List One.\n");
        out.append("# decision: D01-2 — docs/step_01_domain_contracts.md#decisions-and-outputs\n");
        out.append("# source: ISO 4217 List One (SIX Financial Information AG), published ").append(published).append('\n');
        out.append("# source-sha256: ").append(sha256(bytes)).append('\n');
        out.append("# generated-by: libs/money/scripts/GenerateMinorUnitTable.java\n");
        out.append("# skipped codes without numeric minor units: ").append(String.join(",", nonNumeric)).append('\n');
        out.append("# skipped entries without a currency code: ").append(String.join("; ", withoutCode)).append('\n');
        out.append("# format: CODE,DIGITS sorted by code; do not edit by hand, regenerate instead\n");
        digits.forEach((code, value) -> out.append(code).append(',').append(value).append('\n'));

        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
        System.out.println("wrote " + digits.size() + " currencies to " + output);
    }

    /** DTDs and external entities are disabled: the downloaded file is untrusted input. */
    private static Document parseSecurely(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent().strip();
        return value.isEmpty() ? null : value;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            System.err.println("GenerateMinorUnitTable: " + message);
            System.exit(1);
        }
    }
}
