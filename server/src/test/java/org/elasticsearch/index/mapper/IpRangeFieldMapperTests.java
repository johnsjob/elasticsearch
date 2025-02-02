/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.mapper;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexableField;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.junit.AssumptionViolatedException;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class IpRangeFieldMapperTests extends RangeFieldMapperTests {

    @Override
    protected void minimalMapping(XContentBuilder b) throws IOException {
        b.field("type", "ip_range");
    }

    @Override
    protected XContentBuilder rangeSource(XContentBuilder in) throws IOException {
        return in.startObject("field").field("gt", "::ffff:c0a8:107").field("lt", "2001:db8::").endObject();
    }

    @Override
    protected String storedValue() {
        return InetAddresses.toAddrString(InetAddresses.forString("192.168.1.7"))
            + " : "
            + InetAddresses.toAddrString(InetAddresses.forString("2001:db8:0:0:0:0:0:0"));
    }

    @Override
    protected boolean supportsCoerce() {
        return false;
    }

    @Override
    protected Object rangeValue() {
        return "192.168.1.7";
    }

    @Override
    protected boolean supportsDecimalCoerce() {
        return false;
    }

    public void testStoreCidr() throws Exception {

        DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> b.field("type", "ip_range").field("store", true)));

        final Map<String, String> cases = new HashMap<>();
        cases.put("192.168.0.0/15", "192.169.255.255");
        cases.put("192.168.0.0/16", "192.168.255.255");
        cases.put("192.168.0.0/17", "192.168.127.255");
        for (final Map.Entry<String, String> entry : cases.entrySet()) {
            ParsedDocument doc = mapper.parse(source(b -> b.field("field", entry.getKey())));
            List<IndexableField> fields = doc.rootDoc().getFields("field");
            assertEquals(3, fields.size());
            IndexableField dvField = fields.get(0);
            assertEquals(DocValuesType.BINARY, dvField.fieldType().docValuesType());
            IndexableField pointField = fields.get(1);
            assertEquals(2, pointField.fieldType().pointIndexDimensionCount());
            IndexableField storedField = fields.get(2);
            assertTrue(storedField.fieldType().stored());
            String strVal = InetAddresses.toAddrString(InetAddresses.forString("192.168.0.0"))
                + " : "
                + InetAddresses.toAddrString(InetAddresses.forString(entry.getValue()));
            assertThat(storedField.stringValue(), containsString(strVal));
        }
    }

    @Override
    public void testNullBounds() throws IOException {
        DocumentMapper mapper = createDocumentMapper(fieldMapping(b -> {
            minimalMapping(b);
            b.field("store", true);
        }));

        ParsedDocument bothNull = mapper.parse(source(b -> b.startObject("field").nullField("gte").nullField("lte").endObject()));
        assertThat(storedValue(bothNull), equalTo("[:: : ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]"));

        ParsedDocument onlyFromIPv4 = mapper.parse(
            source(b -> b.startObject("field").field("gte", rangeValue()).nullField("lte").endObject())
        );
        assertThat(storedValue(onlyFromIPv4), equalTo("[192.168.1.7 : 255.255.255.255]"));

        ParsedDocument onlyToIPv4 = mapper.parse(
            source(b -> b.startObject("field").nullField("gte").field("lte", rangeValue()).endObject())
        );
        assertThat(storedValue(onlyToIPv4), equalTo("[0.0.0.0 : 192.168.1.7]"));

        ParsedDocument onlyFromIPv6 = mapper.parse(
            source(b -> b.startObject("field").field("gte", "2001:db8::").nullField("lte").endObject())
        );
        assertThat(storedValue(onlyFromIPv6), equalTo("[2001:db8:: : ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]"));

        ParsedDocument onlyToIPv6 = mapper.parse(
            source(b -> b.startObject("field").nullField("gte").field("lte", "2001:db8::").endObject())
        );
        assertThat(storedValue(onlyToIPv6), equalTo("[:: : 2001:db8::]"));
    }

    @SuppressWarnings("unchecked")
    public void testValidSyntheticSource() throws IOException {
        CheckedConsumer<XContentBuilder, IOException> mapping = b -> {
            b.startObject("field");
            b.field("type", "ip_range");
            if (rarely()) {
                b.field("index", false);
            }
            if (rarely()) {
                b.field("store", false);
            }
            b.endObject();
        };

        var values = randomList(1, 5, this::generateValue);
        var inputValues = values.stream().map(Tuple::v1).toList();
        var expectedValues = values.stream().map(Tuple::v2).toList();

        var source = getSourceFor(mapping, inputValues);

        // This is the main reason why we need custom logic.
        // IP ranges are serialized into binary doc values in unpredictable order
        // because API uses a set.
        // So this assert needs to be not sensitive to order and in "reference"
        // implementation of tests from MapperTestCase it is.
        var actual = source.source().get("field");
        if (inputValues.size() == 1) {
            assertEquals(expectedValues.get(0), actual);
        } else {
            assertThat(actual, instanceOf(List.class));
            assertTrue(((List<Object>) actual).containsAll(new HashSet<>(expectedValues)));
        }
    }

    private Tuple<Object, Object> generateValue() {
        String cidr = randomCidrBlock();
        InetAddresses.IpRange range = InetAddresses.parseIpRangeFromCidr(cidr);

        var includeFrom = randomBoolean();
        var includeTo = randomBoolean();

        Object input;
        // "to" field always comes first.
        Map<String, Object> output = new LinkedHashMap<>();
        if (randomBoolean()) {
            // CIDRs are always inclusive ranges.
            input = cidr;
            output.put("gte", InetAddresses.toAddrString(range.lowerBound()));
            output.put("lte", InetAddresses.toAddrString(range.upperBound()));
        } else {
            var fromKey = includeFrom ? "gte" : "gt";
            var toKey = includeTo ? "lte" : "lt";
            var from = rarely() ? null : InetAddresses.toAddrString(range.lowerBound());
            var to = rarely() ? null : InetAddresses.toAddrString(range.upperBound());
            input = (ToXContent) (builder, params) -> builder.startObject().field(fromKey, from).field(toKey, to).endObject();

            // When ranges are stored, they are always normalized to include both ends.
            // `includeFrom` and `includeTo` here refers to user input.
            //
            // Range values are not properly normalized for default values
            // which results in off by one error here.
            // So "gte": null and "gt": null both result in "gte": MIN_VALUE.
            // This is a bug, see #107282.
            if (from == null) {
                output.put("gte", InetAddresses.toAddrString((InetAddress) rangeType().minValue()));
            } else {
                var rawFrom = range.lowerBound();
                var adjustedFrom = includeFrom ? rawFrom : (InetAddress) RangeType.IP.nextUp(rawFrom);
                output.put("gte", InetAddresses.toAddrString(adjustedFrom));
            }
            if (to == null) {
                output.put("lte", InetAddresses.toAddrString((InetAddress) rangeType().maxValue()));
            } else {
                var rawTo = range.upperBound();
                var adjustedTo = includeTo ? rawTo : (InetAddress) RangeType.IP.nextDown(rawTo);
                output.put("lte", InetAddresses.toAddrString(adjustedTo));
            }
        }

        return Tuple.tuple(input, output);
    }

    public void testInvalidSyntheticSource() {
        Exception e = expectThrows(IllegalArgumentException.class, () -> createDocumentMapper(syntheticSourceMapping(b -> {
            b.startObject("field");
            b.field("type", "ip_range");
            b.field("doc_values", false);
            b.endObject();
        })));
        assertThat(
            e.getMessage(),
            equalTo("field [field] of type [ip_range] doesn't support synthetic source because it doesn't have doc values")
        );
    }

    @Override
    protected SyntheticSourceSupport syntheticSourceSupport(boolean ignoreMalformed) {
        throw new AssumptionViolatedException("custom version of synthetic source tests is implemented");
    }

    private static String randomCidrBlock() {
        boolean ipv4 = randomBoolean();

        InetAddress address = randomIp(ipv4);
        // exclude smallest prefix lengths to avoid empty ranges
        int prefixLength = ipv4 ? randomIntBetween(0, 30) : randomIntBetween(0, 126);

        return InetAddresses.toCidrString(address, prefixLength);
    }

    @Override
    protected RangeType rangeType() {
        return RangeType.IP;
    }

    @Override
    protected IngestScriptSupport ingestScriptSupport() {
        throw new AssumptionViolatedException("not supported");
    }
}
