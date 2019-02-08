package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

import algorithms.Simulator;
import nl.utwente.ewi.fmt.EXPRES.MarkovReducedLTS;

public class MakeTraLab {
	private final ArrayList<Map<Integer, String>> transitions = new ArrayList<>();
	private final ArrayList<String> markings = new ArrayList<>();
	private final LTS l;
	private BitSet notErgodic;
	private int transitionCount = 0;

	public MakeTraLab(LTS lts)
	{
		if (lts instanceof MarkovReducedLTS)
			l = (MarkovReducedLTS)lts;
		else
			l = new MarkovReducedLTS(lts);
	}

	public MakeTraLab(LTS lts, boolean unsafe)
	{
		if (lts instanceof MarkovReducedLTS)
			l = (MarkovReducedLTS)lts;
		else if (unsafe && (lts instanceof Composition))
			l = new MarkovianComposition((Composition)lts);
		else
			l = new MarkovReducedLTS(lts);
	}

	private class Explorer extends Thread {
		public final LinkedBlockingDeque<int[]> toExplore;
		public final ConcurrentHashMap<int[], Object[]> explored;
		/* Explored:
		 * [length-1] - state label,
		 * [length-2] - marking,
		 * rest       - Set<LTS.Transition>
		 */

		public Explorer(LinkedBlockingDeque<int[]> toExplore,
		                ConcurrentHashMap<int[], Object[]> explored)
		{
			this.toExplore = toExplore;
			this.explored = explored;
		}

		public void run () {
			int[] state;
			while (true) try {
				state = toExplore.takeLast();
				break;
			} catch (InterruptedException e) {
			}
			while (state.length > 0) {
				if (explored.containsKey(state))
					continue;
				String sState = Composition.stateString(state);
				Map<?, Integer> vals = l.getVarValues(state);
				String marking = "";
				for (Map.Entry<?, Integer> v : vals.entrySet()){
					if (v.getValue() != 0)
						marking += " " + v.getKey();
				}
				if (marking.length() == 0)
					marking = null;
				vals = null;
				Set<LTS.Transition> s = l.getTransitions(state);
				Object[] ret = new Object[s.size() + 2];
				s.toArray(ret);
				s = null;
				ret[ret.length - 1] = sState;
				ret[ret.length - 2] = marking;
				explored.put(state, ret);
				ret = null;
				while (true) try {
					state = toExplore.takeLast();
					break;
				} catch (InterruptedException e) {
				}
			}
			while (true) {
				try {
					toExplore.putLast(state);
					break;
				} catch (InterruptedException e) {
				}
			}
		}
	}

	public void convert(String out) throws IOException
	{
		int numStates;
		
		FileOutputStream traFile, labFile;
		PrintWriter traWriter, labWriter;
		exploreStates();
		numStates = markings.size();
		System.err.format("%d states before removing duplicates\n", numStates);
		collapseDeadends();
		numStates = markings.size();
		System.err.format("%d lefs after collapsing BSCCs\n", numStates);
		while (removeDuplicateStates())
			System.err.format("%d states currently left\n", markings.size());

		numStates = markings.size();
		System.err.format("%d states left after removing duplicates\n", numStates);

		traFile = new FileOutputStream(out + ".tra");
		traWriter = new PrintWriter(traFile);
		labFile = new FileOutputStream(out + ".lab");
		labWriter = new PrintWriter(labFile);
		traWriter.format("STATES %d\nTRANSITIONS %s\n",
		                 numStates,
		                 transitionCount);
		Map<String, Integer> initialValues = l.getVarValues(l.getInitialState());
		labWriter.println("#DECLARATION");
		for (String v : initialValues.keySet())
			labWriter.println(v);
		labWriter.println("#END");
		for (int i = 0; i < numStates; i++)
			transitions.add(null);
		printStates(traWriter, labWriter);
		labWriter.close();
		traWriter.close();
		//checkErgodic();
	}

	private void exploreStates()
	{
		TreeMap<String, Integer> stateNums = new TreeMap<>();
		LinkedBlockingDeque<int[]> toExplore;
		ConcurrentHashMap<int[], Object[]> explored;
		toExplore = new LinkedBlockingDeque<>();
		explored = new ConcurrentHashMap<>();
		Explorer[] threads = new Explorer[Simulator.coresToUse];
		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Explorer(toExplore, explored);
			threads[i].start();
		}
		int state[] = l.getInitialState();
		while (true) {
			try {
				threads[0].toExplore.putLast(state);
				break;
			} catch (InterruptedException e) {
			}
		}
		exploreStates(stateNums, state, threads[0]);
		while (true) {
			try {
				threads[0].toExplore.putLast(new int[0]);
				break;
			} catch (InterruptedException e) {
			}
		}
	}

	private Integer exploreStates(Map<String, Integer> stateNums,
	                              int[] state, Explorer explorer)
	{
		Object[] stateInfo;
		while ((stateInfo = explorer.explored.get(state)) == null)
			Thread.yield();
		Object tmp = stateInfo[stateInfo.length - 1];
		assert(tmp instanceof String);
		String sState = (String)tmp;
		Integer stateNum = stateNums.get(sState);
		if (stateNum != null)
			return stateNum;
		if (stateNums.size() > 0 && (stateNums.size() & 32767) == 0) {
			System.err.format("Processed %d states\n",
					stateNums.size());
		}
		stateNum = stateNums.size();
		stateNums.put(sState, stateNum);

		tmp = stateInfo[stateInfo.length - 2];
		if (tmp != null) {
			assert(tmp instanceof String);
			markings.add((String)tmp);
		} else {
			markings.add(null);
		}

		for (int i = stateInfo.length - 3; i >= 0; i--) {
			tmp = stateInfo[i];
			assert(tmp instanceof LTS.Transition);
			LTS.Transition t = (LTS.Transition)tmp;
			while (true)  {
				try {
					explorer.toExplore.put(t.target);
					break;
				} catch (InterruptedException e) {
				}
			}
		}
		TreeMap<Integer, String> ts = new TreeMap<>();
		TreeSet<Integer> lTargets = new TreeSet<>();
		transitions.add(ts);
		transitionCount += stateInfo.length - 1;
		for (int i = stateInfo.length - 3; i >= 0; i--) {
			tmp = stateInfo[i];
			assert(tmp instanceof LTS.Transition);
			LTS.Transition t = (LTS.Transition)tmp;
			Integer target = exploreStates(stateNums, t.target, explorer);
			String label = t.label;
			if (ts.containsKey(target))
				label = MarkovReducedLTS.addLabels(ts.get(target), label);
			ts.put(target, label);
			lTargets.add(target);
		}
		return stateNum;
	}

	private void printStates(PrintWriter traWriter,
	                               PrintWriter labWriter)
	{
		int maxState = markings.size();
		for (int i = 0; i < maxState; i++) {
			Map<Integer, String> ts = transitions.get(i);
			for (Integer target : ts.keySet()) {
				String label = ts.get(target);
				if (label.charAt(0) == 'r')
					label = label.substring(1);
				traWriter.format("%d %d %s\n",
				                 i + 1, target + 1, label);
			}
			String marking = markings.get(i);
			if (marking != null)
				labWriter.format("%d%s\n", i + 1, marking);
		}
	}

	private boolean removeDuplicateStates()
	{
		HashMap<Object, Integer> states = new HashMap<>();
		List<Set<Integer>> predecessors = new ArrayList<>(markings.size());
		List<int[]> dups = new ArrayList<>();

		int maxState = markings.size();
		for (int i = 0; i < maxState; i++)
			predecessors.add(new TreeSet<Integer>());
		for (int i = 0; i < maxState; i++) {
			Integer s = i;
			Object stateInfo;
			Map<Integer, String> ts = transitions.get(i);
			for (Integer t : ts.keySet())
				predecessors.get(t).add(s);
			String marking = markings.get(i);
			if (marking == null)
				stateInfo = ts;
			else
				stateInfo = List.of(ts, marking);
			Integer existing = states.get(stateInfo);
			if (existing == null)
				states.put(stateInfo, i);
			else
				dups.add(new int[]{i, existing});
		}
		if (dups.isEmpty())
			return false;
		states = null;

		dups.forEach((data) -> {
			int dup = data[0], merged = data[1];
			int repl = transitions.size() - 1;
			Integer iRepl = repl, iDup = dup, iMerged = merged;

			if (dup >= transitions.size())
				return;
			Map<Integer, String> ts = transitions.get(dup);
			String marking = markings.get(dup);
			Object dupInfo;
			if (marking == null)
				dupInfo = ts;
			else
				dupInfo = List.of(ts, marking);

			ts = transitions.get(merged);
			marking = markings.get(merged);
			Object mergedInfo;
			if (marking == null)
				mergedInfo = ts;
			else
				mergedInfo = List.of(ts, marking);

			if (!mergedInfo.equals(dupInfo)) {
				System.err.println("Different: " + mergedInfo + " and " + dupInfo);
				return;
			}
			Set<Integer> mPreds = predecessors.get(merged);
			for (Integer s : predecessors.get(dup)) {
				Map<Integer, String> t = transitions.get(s);
				String dLabel = t.remove(iDup);
				String mLabel = t.get(iMerged);
				String nLabel = MarkovReducedLTS.addLabels(mLabel, dLabel);
				if (nLabel == null)
					throw new AssertionError("Dup error " + dLabel + " -- " + mLabel + " @ " + s + " for " + merged + "<-" + dup + "<-" + repl);
				t.put(iMerged, nLabel);
				mPreds.add(s);
			}
			for (Integer s : predecessors.get(repl)) {
				Map<Integer, String> t = transitions.get(s);
				String rLabel = t.remove(iRepl);
				if (rLabel == null)
					continue;
				t.put(iDup, rLabel);
			}
			/* Dup is no longer a predecessor of anything.
			 * We don't need to add a new predecessor, since
			 * by definition 'merged' is already a
			 * predecessor (otherwise we couldn't have
			 * merged them).
			 */
			for (Integer s : transitions.get(dup).keySet()) {
				Set<Integer> preds = predecessors.get((int)s);
				preds.remove(iDup);
			}
			for (Integer s : transitions.get(repl).keySet()) {
				Set<Integer> preds = predecessors.get((int)s);
				preds.remove(iRepl);
				if (dup != repl)
					preds.add(iDup);
			}

			predecessors.set(dup, predecessors.get(repl));
			predecessors.remove(repl);
			transitions.set(dup, transitions.get(repl));
			transitions.remove(repl);
			markings.set(dup, markings.get(repl));
			markings.remove(repl);
		});
		return true;
	}

	private void checkErgodic()
	{
		BitSet notErgodic = new BitSet(markings.size());
		notErgodic.set(1, markings.size());
		boolean changed;
		do {
			changed = false;
			int state = notErgodic.nextSetBit(0);
			while (state != -1) {
				for (int tgt : transitions.get(state).keySet())
				{
					if (!notErgodic.get(tgt)) {
						notErgodic.clear(state);
						changed = true;
						break;
					}
				}
				state = notErgodic.nextSetBit(state + 1);
			}
		} while (changed);
		if (!notErgodic.isEmpty()) {
			int s = notErgodic.nextSetBit(0);
			while (s != -1) {
				System.err.format("%d Cannot return to initial state.\n", s);
				s = notErgodic.nextSetBit(s + 1);
			}
		}
	}

	private void collapseDeadends()
	{
		int numStates = markings.size();
		int minDeadend = 0;
		BitSet canChange = new BitSet(markings.size());
		boolean changed;
		do {
			changed = false;
			int s = canChange.nextClearBit(minDeadend);
			minDeadend = s;
			while (s < numStates) {
				String mark = markings.get(s);
				for (int t : transitions.get(s).keySet()) {
					if (canChange.get(t)) {
						canChange.set(s);
						changed = true;
						break;
					}
					String tMark = markings.get(t);
					if (tMark == mark)
						continue;
					if (tMark != null && tMark.equals(mark))
						continue;
					canChange.set(s);
					changed = true;
					break;
				}
				s = canChange.nextClearBit(s + 1);
			}
		} while (changed);
		TreeMap<String, Integer> stateLabels = new TreeMap<>();
		Map<Integer, String> emptyTransitions = Collections.emptyMap();
		int s = minDeadend;
		while (s < numStates) {
			String mark = markings.get(s);
			if (mark == null)
				mark = "";
			transitions.set(s, emptyTransitions);
			if (!stateLabels.containsKey(mark)) {
				stateLabels.put(mark, minDeadend);
				canChange.set(minDeadend++);
				minDeadend = canChange.nextClearBit(minDeadend);
			}
			s = canChange.nextClearBit(s + 1);
		}
		if (minDeadend == numStates)
			return;
		HashMap<Integer, Integer> renames = new HashMap<>();
		s = canChange.nextClearBit(minDeadend);
		int i = numStates - 1;
		while (s < numStates) {
			while (i > s && !canChange.get(i)) {
				String mark = markings.remove(i);
				if (mark == null)
					mark = "";
				transitions.remove(i);
				renames.put(i, stateLabels.get(mark));
				i--;
				numStates--;
			}
			Map<Integer, String> ts = transitions.remove(i);
			String sMark = markings.get(s);
			if (sMark == null)
				sMark = "";
			String iMark = markings.remove(i);
			renames.put(i, s);
			renames.put(s, stateLabels.get(sMark));
			if (s != i) {
				transitions.set(s, ts);
				markings.set(s, iMark);
			}
			i--;
			numStates--;
			s = canChange.nextClearBit(s + 1);
		}
		HashMap<Integer, String> tmpRenames = new HashMap<>();
		for (i = 0; i < numStates; i++) {
			Map<Integer, String> ts = transitions.get(i);
			Iterator<Integer> trans_it = ts.keySet().iterator();
			while (trans_it.hasNext()) {
				Integer tgt = trans_it.next();
				Integer rename = renames.get(tgt);
				if (rename != null) {
					String label = ts.get(tgt);
					String rLabel = tmpRenames.get(rename);
					label = MarkovReducedLTS.addLabels(label, rLabel);
					tmpRenames.put(rename, label);
					trans_it.remove();
				}
			}
			if (tmpRenames.isEmpty())
				continue;
			for (Map.Entry<Integer, String> rn : tmpRenames.entrySet()) {
				Integer tgt = rn.getKey();
				String nLabel = rn.getValue();
				String oLabel = ts.get(tgt);
				ts.put(tgt, MarkovReducedLTS.addLabels(nLabel, oLabel));
			}
			tmpRenames.clear();
		}
		for (Map.Entry<String, Integer> p : stateLabels.entrySet()) {
			String mark = p.getKey();
			if (mark.length() == 0)
				mark = null;
			markings.set(p.getValue(), mark);
		}
	}
}
