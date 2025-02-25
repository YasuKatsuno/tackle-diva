package io.tackle.diva.analysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.intset.IntPair;

import io.tackle.diva.Constants;
import io.tackle.diva.Context;
import io.tackle.diva.Framework;
import io.tackle.diva.Trace;
import io.tackle.diva.Util;

public class SpringBootAnalysis {

    public static List<IMethod> getEntries(IClassHierarchy cha) throws IOException {
        List<IMethod> entries = new ArrayList<>();

        for (IClass c : cha) {
            if (!Util.any(Util.getAnnotations(c), a -> a.getType().getName() == Constants.LSpringController))
                continue;
            for (IMethod m : c.getDeclaredMethods()) {
                if (m.isStatic())
                    continue;
                if (m.isInit())
                    continue;
                entries.add(m);
            }

        }
        return entries;

    }

    public static List<IMethod> getInits(IClassHierarchy cha) throws IOException {
        List<IMethod> entries = new ArrayList<>();

        for (IClass c : cha) {
            for (IMethod m : c.getDeclaredMethods()) {
                if (!m.isInit())
                    continue;
                if (!Util.any(Util.getAnnotations(m), a -> a.getType().getName() == Constants.LSpringAutowired))
                    continue;
                entries.add(m);
            }
        }
        return entries;

    }

    public static Context.CallSiteVisitor getTransactionAnalysis(Framework fw, Context context) {
        return context.new CallSiteVisitor() {

            @SuppressWarnings("unused")
            @Override
            public void visitCallSite(Trace trace) {

                CGNode node = trace.node();
                CallSiteReference site = trace.site();
                MethodReference ref = site.getDeclaredTarget();
                boolean isSimpleInsert = false;
                int pos = -1;
                if (ref.getDeclaringClass().getName() == Constants.LSpringJdbcTemplate) {
                    if (ref.getName() == Constants.queryForObject || ref.getName() == Constants.update) {
                        pos = 0;
                    }
                }
                if (ref.getDeclaringClass().getName() == Constants.LSpringSimpleJdbcInsert) {
                    if (ref.getName() == Constants.execute) {
                        pos = 0;
                        isSimpleInsert = true;
                    }
                }
                if (pos >= 0) {
                    if (!fw.txStarted()) {
                        fw.reportSqlStatement(trace, "BEGIN");
                    }
                    if (isSimpleInsert) {
                        analyzeSimpleInsert(fw, trace, site);
                    } else {
                        JDBCAnalysis.analyzeSqlStatement(fw, trace, site, pos);
                    }
                }

            }

            @Override
            public void visitExit(Trace trace) {
                if (!fw.txStarted())
                    return;
                IClass c = trace.node().getMethod().getDeclaringClass();
                if (!Util.any(Util.getAnnotations(c), a -> a.getType().getName() == Constants.LSpringTransactional))
                    return;
                if (trace.parent() == null || !Util.any(trace.parent(), t -> {
                    IClass d = t.node().getMethod().getDeclaringClass();
                    return Util.any(Util.getAnnotations(d),
                            a -> a.getType().getName() == Constants.LSpringTransactional);
                })) {
                    fw.reportSqlStatement(trace, "COMMIT");
                    fw.reportTxBoundary();
                }
            }
        };
    }

    public static void analyzeSimpleInsert(Framework fw, Trace trace, CallSiteReference site) {
        String sql = calculateSimpleInsert(fw, trace.new Val(trace.instrFromSite(site)), new HashSet<>());
        fw.reportSqlStatement(trace, sql);
    }

    public static String calculateSimpleInsert(Framework fw, Trace.Val value, Set<IntPair> visited) {

        if (value == null) {
            return "??";
        }

        if (value.isConstant()) {
            return value.constant().toString();
        }

        SSAInstruction instr = value.instr();

        if (instr instanceof SSANewInstruction) {
            SSAAbstractInvokeInstruction init = (SSAAbstractInvokeInstruction) value.trace().node().getIR()
                    .getInstructions()[instr.iIndex() + 1];
            if (init.getDeclaredTarget().getDeclaringClass().getName() == Constants.LSpringBeanSource) {
                IClass c = value.trace().inferType(fw, init.getUse(1));
                if (c != null) {
                    String s = String.join(", ", Util.map(c.getDeclaredInstanceFields(), f -> f.getName().toString()));
                    String t = String.join(", ", Util.map(c.getDeclaredInstanceFields(), f -> "?"));
                    return "(" + s + ") values (" + t + ")";
                }
            }
        }

        if (instr instanceof SSAAbstractInvokeInstruction) {
            SSAAbstractInvokeInstruction invoke = (SSAAbstractInvokeInstruction) instr;
            MethodReference ref = invoke.getDeclaredTarget();
            if (ref.getName() == Constants.execute) {
                return "insert into " + calculateSimpleInsert(fw, value.getDef(invoke.getUse(0)), visited) + " "
                        + calculateSimpleInsert(fw, value.getDef(invoke.getUse(1)), visited);
            }
            if (ref.getName() == Constants.withTableName) {
                return calculateSimpleInsert(fw, value.getDef(invoke.getUse(1)), visited);
            }
            if (ref.getName() == Constants.usingGeneratedKeyColumns) {
                return calculateSimpleInsert(fw, value.getDef(invoke.getUse(0)), visited);
            }

        } else if (instr instanceof SSAGetInstruction) {
            Trace.Val v = JDBCAnalysis.pointerAnalysis(fw, value.trace(), (SSAGetInstruction) instr);
            if (v != null) {
                return calculateSimpleInsert(fw, v, visited);
            }

        } else if (instr instanceof SSAPhiInstruction) {
            SSAPhiInstruction phi = (SSAPhiInstruction) instr;
            Trace.Val lhs = value.getDef(phi.getUse(0));
            Trace.Val rhs = value.getDef(phi.getUse(1));
            if (lhs.isConstant()) {
                return calculateSimpleInsert(fw, lhs, visited);
            } else if (rhs.isConstant()) {
                return calculateSimpleInsert(fw, rhs, visited);
            }
            IntPair key = IntPair.make(value.trace().node().getGraphNodeId(), lhs.instr().iIndex());
            if (visited.contains(key)) {
                return calculateSimpleInsert(fw, rhs, visited);
            }
            visited.add(key);
            return calculateSimpleInsert(fw, lhs, visited);
        }

        return "??";
    }

}
