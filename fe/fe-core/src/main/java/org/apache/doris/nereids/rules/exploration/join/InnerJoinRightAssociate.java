// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.exploration.join;

import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.rules.exploration.OneExplorationRuleFactory;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.plans.GroupPlan;
import org.apache.doris.nereids.trees.plans.JoinHint;
import org.apache.doris.nereids.trees.plans.JoinType;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.util.JoinUtils;

import com.google.common.base.Preconditions;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rule for inner join RightAssociate.
 */
public class InnerJoinRightAssociate extends OneExplorationRuleFactory {
    //       topJoin        newTopJoin
    //       /     \         /     \
    //  bottomJoin  C  ->   A   newBottomJoin
    //   /    \                     /    \
    //  A      B                   B      C

    public static final InnerJoinRightAssociate INSTANCE = new InnerJoinRightAssociate();

    @Override
    public Rule build() {
        return innerLogicalJoin(innerLogicalJoin(), group())
                .when(InnerJoinRightAssociate::checkReorder)
                .whenNot(join -> join.hasJoinHint() || join.left().hasJoinHint())
                .then(topJoin -> {
                    LogicalJoin<GroupPlan, GroupPlan> bottomJoin = topJoin.left();
                    GroupPlan a = bottomJoin.left();
                    GroupPlan b = bottomJoin.right();
                    GroupPlan c = topJoin.right();

                    // Split condition
                    Set<Slot> bcOutputSet = JoinUtils.getJoinOutputSet(b, c);
                    Map<Boolean, List<Expression>> hashConjunctsSplit = Stream.concat(
                                    topJoin.getHashJoinConjuncts().stream(),
                                    bottomJoin.getHashJoinConjuncts().stream())
                            .collect(Collectors.partitioningBy(condition -> {
                                Set<Slot> usedSlot = condition.getInputSlots();
                                return bcOutputSet.containsAll(usedSlot);
                            }));

                    Map<Boolean, List<Expression>> otherConjunctsSplit = Stream.concat(
                                    topJoin.getOtherJoinConjuncts().stream(),
                                    bottomJoin.getOtherJoinConjuncts().stream())
                            .collect(Collectors.partitioningBy(condition -> {
                                Set<Slot> usedSlot = condition.getInputSlots();
                                return bcOutputSet.containsAll(usedSlot);
                            }));

                    List<Expression> newBottomHashJoinConjuncts = hashConjunctsSplit.get(true);
                    List<Expression> newTopHashJoinConjuncts = hashConjunctsSplit.get(false);
                    Preconditions.checkArgument(newTopHashJoinConjuncts.size() > 0);
                    if (newBottomHashJoinConjuncts.size() == 0) {
                        return null;
                    }

                    List<Expression> newBottomOtherJoinConjuncts = otherConjunctsSplit.get(true);
                    List<Expression> newTopOtherJoinConjuncts = otherConjunctsSplit.get(false);

                    LogicalJoin<GroupPlan, GroupPlan> newBottomJoin = new LogicalJoin<>(JoinType.INNER_JOIN,
                            newBottomHashJoinConjuncts, newBottomOtherJoinConjuncts, JoinHint.NONE,
                            b, c, bottomJoin.getJoinReorderContext());
                    newBottomJoin.getJoinReorderContext().setHasCommute(false);
                    newBottomJoin.getJoinReorderContext().setHasRightAssociate(false);
                    newBottomJoin.getJoinReorderContext().setHasLeftAssociate(false);
                    newBottomJoin.getJoinReorderContext().setHasExchange(false);

                    LogicalJoin<GroupPlan, LogicalJoin<GroupPlan, GroupPlan>> newTopJoin = new LogicalJoin<>(
                            JoinType.INNER_JOIN, newTopHashJoinConjuncts, newTopOtherJoinConjuncts, JoinHint.NONE,
                            a, newBottomJoin, topJoin.getJoinReorderContext());
                    newTopJoin.getJoinReorderContext().setHasRightAssociate(true);
                    newTopJoin.getJoinReorderContext().setHasCommute(false);

                    return newTopJoin;
                }).toRule(RuleType.LOGICAL_INNER_JOIN_RIGHT_ASSOCIATIVE);
    }

    /**
     * Check JoinReorderContext.
     */
    public static boolean checkReorder(LogicalJoin<? extends Plan, GroupPlan> topJoin) {
        return !topJoin.getJoinReorderContext().hasCommute()
                && !topJoin.getJoinReorderContext().hasRightAssociate()
                && !topJoin.getJoinReorderContext().hasRightAssociate()
                && !topJoin.getJoinReorderContext().hasExchange();
    }
}
