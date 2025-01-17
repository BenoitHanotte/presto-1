/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.iceberg;

import com.google.common.base.VerifyException;
import io.airlift.slice.Slice;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.EquatableValueSet;
import io.prestosql.spi.predicate.Marker;
import io.prestosql.spi.predicate.Range;
import io.prestosql.spi.predicate.SortedRangeSet;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.predicate.ValueSet;
import io.prestosql.spi.type.DateType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarbinaryType;
import io.prestosql.spi.type.VarcharType;
import org.apache.iceberg.expressions.Expression;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.predicate.Marker.Bound.ABOVE;
import static io.prestosql.spi.predicate.Marker.Bound.BELOW;
import static io.prestosql.spi.predicate.Marker.Bound.EXACTLY;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static org.apache.iceberg.expressions.Expressions.alwaysFalse;
import static org.apache.iceberg.expressions.Expressions.alwaysTrue;
import static org.apache.iceberg.expressions.Expressions.and;
import static org.apache.iceberg.expressions.Expressions.equal;
import static org.apache.iceberg.expressions.Expressions.greaterThan;
import static org.apache.iceberg.expressions.Expressions.greaterThanOrEqual;
import static org.apache.iceberg.expressions.Expressions.isNull;
import static org.apache.iceberg.expressions.Expressions.lessThan;
import static org.apache.iceberg.expressions.Expressions.lessThanOrEqual;
import static org.apache.iceberg.expressions.Expressions.not;
import static org.apache.iceberg.expressions.Expressions.notEqual;
import static org.apache.iceberg.expressions.Expressions.or;

public final class ExpressionConverter
{
    private ExpressionConverter() {}

    public static Expression toIcebergExpression(TupleDomain<IcebergColumnHandle> tupleDomain, ConnectorSession session)
    {
        if (tupleDomain.isAll()) {
            return alwaysTrue();
        }
        if (tupleDomain.getDomains().isEmpty()) {
            return alwaysFalse();
        }
        Map<IcebergColumnHandle, Domain> domainMap = tupleDomain.getDomains().get();
        Expression expression = alwaysTrue();
        for (Map.Entry<IcebergColumnHandle, Domain> entry : domainMap.entrySet()) {
            IcebergColumnHandle columnHandle = entry.getKey();
            Domain domain = entry.getValue();
            expression = and(expression, toIcebergExpression(columnHandle.getName(), columnHandle.getType(), domain, session));
        }
        return expression;
    }

    private static Expression toIcebergExpression(String columnName, Type type, Domain domain, ConnectorSession session)
    {
        if (domain.isAll()) {
            return alwaysTrue();
        }
        if (domain.getValues().isNone()) {
            return domain.isNullAllowed() ? isNull(columnName) : alwaysFalse();
        }

        if (domain.getValues().isAll()) {
            return domain.isNullAllowed() ? alwaysTrue() : not(isNull(columnName));
        }

        ValueSet domainValues = domain.getValues();
        Expression expression = null;
        if (domain.isNullAllowed()) {
            expression = isNull(columnName);
        }

        if (domainValues instanceof EquatableValueSet) {
            expression = firstNonNull(expression, alwaysFalse());
            EquatableValueSet valueSet = (EquatableValueSet) domainValues;
            if (valueSet.isWhiteList()) {
                // if whitelist is true than this is a case of "in", otherwise this is a case of "not in".
                return or(expression, equal(columnName, valueSet.getValues()));
            }
            return or(expression, notEqual(columnName, valueSet.getValues()));
        }

        if (domainValues instanceof SortedRangeSet) {
            List<Range> orderedRanges = ((SortedRangeSet) domainValues).getOrderedRanges();
            expression = firstNonNull(expression, alwaysFalse());

            for (Range range : orderedRanges) {
                Marker low = range.getLow();
                Marker high = range.getHigh();
                Marker.Bound lowBound = low.getBound();
                Marker.Bound highBound = high.getBound();

                // case col <> 'val' is represented as (col < 'val' or col > 'val')
                if (lowBound == EXACTLY && highBound == EXACTLY) {
                    // case ==
                    if (getValue(type, low, session).equals(getValue(type, high, session))) {
                        expression = or(expression, equal(columnName, getValue(type, low, session)));
                    }
                    else { // case between
                        Expression between = and(
                                greaterThanOrEqual(columnName, getValue(type, low, session)),
                                lessThanOrEqual(columnName, getValue(type, high, session)));
                        expression = or(expression, between);
                    }
                }
                else {
                    if (lowBound == EXACTLY && low.getValueBlock().isPresent()) {
                        // case >=
                        expression = or(expression, greaterThanOrEqual(columnName, getValue(type, low, session)));
                    }
                    else if (lowBound == ABOVE && low.getValueBlock().isPresent()) {
                        // case >
                        expression = or(expression, greaterThan(columnName, getValue(type, low, session)));
                    }

                    if (highBound == EXACTLY && high.getValueBlock().isPresent()) {
                        // case <=
                        if (low.getValueBlock().isPresent()) {
                            expression = and(expression, lessThanOrEqual(columnName, getValue(type, high, session)));
                        }
                        else {
                            expression = or(expression, lessThanOrEqual(columnName, getValue(type, high, session)));
                        }
                    }
                    else if (highBound == BELOW && high.getValueBlock().isPresent()) {
                        // case <
                        if (low.getValueBlock().isPresent()) {
                            expression = and(expression, lessThan(columnName, getValue(type, high, session)));
                        }
                        else {
                            expression = or(expression, lessThan(columnName, getValue(type, high, session)));
                        }
                    }
                }
            }
            return expression;
        }

        throw new VerifyException("Did not expect a domain value set other than SortedRangeSet and EquatableValueSet but got " + domainValues.getClass().getSimpleName());
    }

    private static Object getValue(Type type, Marker marker, ConnectorSession session)
    {
        // TODO: Remove this conversion once we move to next iceberg version
        if (type instanceof DateType) {
            return toIntExact(((Long) marker.getValue()));
        }

        if (type instanceof VarcharType) {
            return ((Slice) marker.getValue()).toStringUtf8();
        }

        if (type instanceof VarbinaryType) {
            return ((Slice) marker.getValue()).getBytes();
        }

        if (type instanceof DecimalType) {
            DecimalType decimalType = (DecimalType) type;
            Object value = requireNonNull(marker.getValue(), "The value of the marker must be non-null");
            if (Decimals.isShortDecimal(decimalType)) {
                checkArgument(value instanceof Long, "A short decimal should be represented by a Long value but was %s", value.getClass().getName());
                return value;
            }
            checkArgument(value instanceof Slice, "A long decimal should be represented by a Slice value but was %s", value.getClass().getName());
            return new BigDecimal(Decimals.decodeUnscaledValue((Slice) value), decimalType.getScale());
        }

        return marker.getValue();
    }
}
