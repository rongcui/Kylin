/*
 * Copyright 2013-2014 eBay Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kylinolap.query.relnode;

import java.util.List;

import net.hydromatic.linq4j.expressions.Blocks;
import net.hydromatic.linq4j.expressions.Expressions;
import net.hydromatic.optiq.rules.java.EnumerableRel;
import net.hydromatic.optiq.rules.java.EnumerableRelImplementor;
import net.hydromatic.optiq.rules.java.PhysType;
import net.hydromatic.optiq.rules.java.PhysTypeImpl;

import org.eigenbase.rel.RelNode;
import org.eigenbase.rel.convert.ConverterRelImpl;
import org.eigenbase.relopt.ConventionTraitDef;
import org.eigenbase.relopt.RelOptCluster;
import org.eigenbase.relopt.RelOptCost;
import org.eigenbase.relopt.RelOptPlanner;
import org.eigenbase.relopt.RelOptTable;
import org.eigenbase.relopt.RelTraitSet;
import org.eigenbase.reltype.RelDataType;

import com.kylinolap.cube.CubeInstance;
import com.kylinolap.query.relnode.OLAPRel.JavaImplementor;
import com.kylinolap.query.relnode.OLAPRel.OLAPImplementor;
import com.kylinolap.query.relnode.OLAPRel.RewriteImplementor;
import com.kylinolap.query.routing.CubeNotFoundException;
import com.kylinolap.query.routing.QueryRouter;
import com.kylinolap.query.schema.OLAPTable;

/**
 * @author xjiang
 */
public class OLAPToEnumerableConverter extends ConverterRelImpl implements EnumerableRel {

    public OLAPToEnumerableConverter(RelOptCluster cluster, RelTraitSet traits, RelNode input) {
        super(cluster, ConventionTraitDef.INSTANCE, traits, input);
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        return new OLAPToEnumerableConverter(getCluster(), traitSet, sole(inputs));
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner) {
        return super.computeSelfCost(planner).multiplyBy(.05);
    }

    @Override
    public Result implement(EnumerableRelImplementor enumImplementor, Prefer pref) {
        // post-order travel children
        OLAPImplementor olapImplementor = new OLAPRel.OLAPImplementor();
        olapImplementor.visitChild(getChild(), this);

        // find cube from olap context
        try {
            for (OLAPContext context : OLAPContext.getThreadLocalContexts()) {
                CubeInstance cube = QueryRouter.findCube(context);
                context.cubeInstance = cube;
                context.cubeDesc = cube.getDescriptor();
            }
        } catch (CubeNotFoundException e) {
            OLAPContext ctx0 = (OLAPContext) OLAPContext.getThreadLocalContexts().toArray()[0];
            if (ctx0 != null && ctx0.olapSchema.hasStarSchemaUrl()) {
                // generate hive result
                return buildHiveResult(enumImplementor, pref, ctx0);
            } else {
                throw e;
            }
        }

        // rewrite query if necessary
        RewriteImplementor rewriteImplementor = new RewriteImplementor();
        rewriteImplementor.visitChild(this, getChild());

        // build java implementation
        EnumerableRel child = (EnumerableRel) getChild();
        JavaImplementor javaImplementor = new JavaImplementor(enumImplementor);
        return javaImplementor.visitChild(this, 0, child, pref);

    }

    private Result buildHiveResult(EnumerableRelImplementor enumImplementor, Prefer pref, OLAPContext context) {
        RelDataType hiveRowType = getRowType();

        context.olapRowType = hiveRowType;
        PhysType physType = PhysTypeImpl.of(enumImplementor.getTypeFactory(), hiveRowType, pref.preferArray());

        RelOptTable factTable = context.firstTableScan.getTable();
        Result result = enumImplementor.result(physType, Blocks.toBlock(Expressions.call(factTable.getExpression(OLAPTable.class), "executeHiveQuery", enumImplementor.getRootExpression())));
        return result;
    }

}
