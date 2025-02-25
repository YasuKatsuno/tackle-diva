/*
Copyright IBM Corporation 2021

Licensed under the Eclipse Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package io.tackle.diva;

import java.util.ArrayList;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.util.intset.BitVector;
import com.ibm.wala.util.intset.IntPair;

public class Context extends ArrayList<Context.Constraint> {

    public abstract class CallSiteVisitor implements Trace.CallSiteVisitor {
        @Override
        public void visitNode(Trace trace) {
            trace.setContext(Context.this);
        }
    }

    public abstract class NodeVisitor implements Trace.NodeVisitor {
        @Override
        public void visitNode(Trace trace) {
            trace.setContext(Context.this);
            visitNode(trace, Context.this);
        }

        public abstract void visitNode(Trace trace, Context context);
    }

    public static interface Constraint {

        default public void report(Report.Named report) {
            report.put(category(), (Report.Named map) -> {
                map.put(type(), (Report values) -> {
                    values.add(value());
                });
            });
        }

        public String category();

        public String type();

        public String value();

    }

    public static interface BranchingConstraint extends Constraint {
        public Iterable<IntPair> fallenThruBranches();

        public Iterable<IntPair> takenBranches();
    }

    public Context(Iterable<Constraint> cs) {
        for (Constraint c : cs)
            add(c);
    }

    public Context() {
    }

    public BitVector calculateReachable(CGNode n) {
        BitVector visited = new BitVector();
        BitVector todo = new BitVector();
        BitVector fallenThru = new BitVector();
        BitVector taken = new BitVector();

        for (Constraint con : this) {
            if (con instanceof BranchingConstraint) {
                BranchingConstraint c = (BranchingConstraint) con;
                for (IntPair key : c.fallenThruBranches()) {
                    if (key.getX() != n.getGraphNodeId())
                        continue;
                    fallenThru.set(key.getY());
                }
                for (IntPair key : c.takenBranches()) {
                    if (key.getX() != n.getGraphNodeId())
                        continue;
                    taken.set(key.getY());
                }
            }
        }

        IR ir = n.getIR();
        int i = 0;

        todo.set(i);

        while (!todo.isZero()) {
            i = todo.nextSetBit(0);
            todo.clear(i);
            visited.set(i);
            if (i >= ir.getInstructions().length)
                continue;
            SSAInstruction instr = ir.getInstructions()[i];

            if (instr instanceof SSAConditionalBranchInstruction) {
                if (!fallenThru.get(i)) {
                    SSAConditionalBranchInstruction c = (SSAConditionalBranchInstruction) instr;
                    if (c.getTarget() >= 0 && !visited.contains(c.getTarget())) {
                        todo.set(c.getTarget());
                    }
                }
                if (taken.get(i)) {
                    continue;
                }
            } else if (instr instanceof SSASwitchInstruction) {
                SSASwitchInstruction c = (SSASwitchInstruction) instr;
                for (int l : c.getCasesAndLabels()) {
                    int j = c.getTarget(l);
                    if (!visited.contains(j)) {
                        todo.set(j);
                    }
                }
                if (!visited.contains(c.getDefault())) {
                    todo.set(c.getDefault());
                }
            } else if (instr instanceof SSAGotoInstruction) {
                SSAGotoInstruction c = (SSAGotoInstruction) instr;
                if (c.getTarget() >= 0 && !visited.contains(c.getTarget())) {
                    todo.set(c.getTarget());
                }
            }
            if (instr == null || instr.isFallThrough()) {

                if (!visited.contains(i + 1)) {
                    todo.set(i + 1);
                }
            }
        }
        return visited;
    }
}
