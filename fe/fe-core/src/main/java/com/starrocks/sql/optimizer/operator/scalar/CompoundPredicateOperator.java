// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.optimizer.operator.scalar;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.OperatorType;
import org.apache.commons.collections.CollectionUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class CompoundPredicateOperator extends PredicateOperator {
    private final CompoundType type;

    // These two filed are used in NormalizePredicateRule to eliminate common CompoundPredicate
    // For Expr tree like below, the CompoundTreeLeafNodeNumber is 5, and compoundTreeUniqueLeave's size is 4.
    //           AND
    //        /        \
    //      AND         AND
    //     /   \       /  \
    // subT1   a+1  And  subT5
    //             /   \
    //          subT3  a+1
    private int compoundTreeLeafNodeNumber;
    private Set<ScalarOperator> compoundTreeUniqueLeaves;

    public CompoundPredicateOperator(CompoundType compoundType, ScalarOperator... arguments) {
        super(OperatorType.COMPOUND, arguments);
        this.type = compoundType;
        Preconditions.checkState(arguments.length >= 1);
        incrDepth(arguments);
    }

    public CompoundPredicateOperator(CompoundType compoundType, List<ScalarOperator> arguments) {
        super(OperatorType.COMPOUND, arguments);
        this.type = compoundType;
        Preconditions.checkState(!CollectionUtils.isEmpty(arguments));
        incrDepth(arguments);
    }

    public CompoundType getCompoundType() {
        return type;
    }

    public Set<ScalarOperator> getCompoundTreeUniqueLeaves() {
        return compoundTreeUniqueLeaves;
    }

    public void setCompoundTreeUniqueLeaves(Set<ScalarOperator> compoundTreeUniqueLeaves) {
        this.compoundTreeUniqueLeaves = compoundTreeUniqueLeaves;
    }

    public int getCompoundTreeLeafNodeNumber() {
        return compoundTreeLeafNodeNumber;
    }

    public void setCompoundTreeLeafNodeNumber(int compoundTreeLeafNodeNumber) {
        this.compoundTreeLeafNodeNumber = compoundTreeLeafNodeNumber;
    }

    @Override
    public <R, C> R accept(ScalarOperatorVisitor<R, C> visitor, C context) {
        return visitor.visitCompoundPredicate(this, context);
    }

    public enum CompoundType {
        AND,
        OR,
        NOT
    }

    public boolean isAnd() {
        return CompoundType.AND.equals(type);
    }

    public boolean isOr() {
        return CompoundType.OR.equals(type);
    }

    public boolean isNot() {
        return CompoundType.NOT.equals(type);
    }

    @Override
    public String toString() {
        if (CompoundType.NOT.equals(type)) {
            return "NOT " + getChild(0).toString();
        } else {
            return getChild(0).toString() + " " + type.toString() + " " + getChild(1).toString();
        }
    }

    @Override
    public String debugString() {
        if (CompoundType.NOT.equals(type)) {
            return "NOT " + getChild(0).debugString();
        } else {
            return getChild(0).debugString() + " " + type.toString() + " " + getChild(1).debugString();
        }
    }

    private List<ScalarOperator> normalizeChildren() {
        List<ScalarOperator> sortedChildren;
        switch (type) {
            case AND:
                sortedChildren = Utils.extractConjuncts(this).stream()
                        .sorted(Comparator.comparingInt(ScalarOperator::hashCode)).collect(Collectors.toList());
                break;
            case OR:
                sortedChildren = Utils.extractDisjunctive(this).stream()
                        .sorted(Comparator.comparingInt(ScalarOperator::hashCode)).collect(Collectors.toList());
                break;
            default:
                sortedChildren = Lists.newArrayList(this.getChildren());
        }
        return sortedChildren;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CompoundPredicateOperator that = (CompoundPredicateOperator) o;
        if (type != that.type) {
            return false;
        }

        List<ScalarOperator> thisArgs = this.normalizeChildren();
        List<ScalarOperator> thatArgs = that.normalizeChildren();
        return Objects.equals(thisArgs, thatArgs);
    }

    @Override
    public boolean equalsSelf(Object o) {
        if (!super.equalsSelf(o)) {
            return false;
        }
        CompoundPredicateOperator that = (CompoundPredicateOperator) o;
        return type == that.type;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (ScalarOperator scalarOperator : this.getChildren()) {
            if (scalarOperator != null) {
                h += scalarOperator.hashCode();
            }
        }
        return Objects.hash(hashCodeSelf(), h);
    }

    @Override
    public int hashCodeSelf() {
        return Objects.hash(super.hashCodeSelf(), type);
    }

    public static ScalarOperator or(Collection<ScalarOperator> nodes) {
        return Utils.createCompound(CompoundPredicateOperator.CompoundType.OR, nodes);
    }

    public static ScalarOperator or(ScalarOperator... nodes) {
        return Utils.createCompound(CompoundPredicateOperator.CompoundType.OR, Arrays.asList(nodes));
    }

    public static ScalarOperator and(Collection<ScalarOperator> nodes) {
        return Utils.createCompound(CompoundPredicateOperator.CompoundType.AND, nodes);
    }

    public static ScalarOperator and(ScalarOperator... nodes) {
        return Utils.createCompound(CompoundPredicateOperator.CompoundType.AND, Arrays.asList(nodes));
    }

    public static ScalarOperator not(ScalarOperator node) {
        return new CompoundPredicateOperator(CompoundType.NOT, node);
    }
}
