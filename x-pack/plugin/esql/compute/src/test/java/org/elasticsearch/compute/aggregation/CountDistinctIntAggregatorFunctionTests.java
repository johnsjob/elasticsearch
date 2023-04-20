/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.CannedSourceOperator;
import org.elasticsearch.compute.operator.Driver;
import org.elasticsearch.compute.operator.PageConsumerOperator;
import org.elasticsearch.compute.operator.SequenceIntBlockSourceOperator;
import org.elasticsearch.compute.operator.SourceOperator;

import java.util.List;
import java.util.stream.LongStream;

import static org.hamcrest.Matchers.closeTo;

public class CountDistinctIntAggregatorFunctionTests extends AggregatorFunctionTestCase {
    @Override
    protected SourceOperator simpleInput(int size) {
        int max = between(1, Math.min(Integer.MAX_VALUE, Integer.MAX_VALUE / size));
        return new SequenceIntBlockSourceOperator(LongStream.range(0, size).mapToInt(l -> between(-max, max)));
    }

    @Override
    protected AggregatorFunction.Factory aggregatorFunction() {
        return AggregatorFunction.COUNT_DISTINCT_INTS;
    }

    @Override
    protected String expectedDescriptionOfAggregator() {
        return "count_distinct of ints";
    }

    @Override
    protected void assertSimpleOutput(List<Block> input, Block result) {
        long expected = input.stream().flatMapToInt(b -> allInts(b)).distinct().count();

        long count = ((LongBlock) result).getLong(0);
        // HLL is an approximation algorithm and precision depends on the number of values computed and the precision_threshold param
        // https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-metrics-cardinality-aggregation.html
        // For a number of values close to 10k and precision_threshold=1000, precision should be less than 10%
        assertThat((double) count, closeTo(expected, expected * 0.1));
    }

    public void testRejectsDouble() {
        try (
            Driver d = new Driver(
                new CannedSourceOperator(Iterators.single(new Page(new DoubleArrayVector(new double[] { 1.0 }, 1).asBlock()))),
                List.of(simple(nonBreakingBigArrays()).get()),
                new PageConsumerOperator(page -> fail("shouldn't have made it this far")),
                () -> {}
            )
        ) {
            expectThrows(Exception.class, d::run);  // ### find a more specific exception type
        }
    }
}
