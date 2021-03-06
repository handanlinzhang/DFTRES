package nl.utwente.ewi.fmt.EXPRES;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntConsumer;

import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

public class Automaton implements LTS {
	/** The initial state of the automaton */
	public final int initState;
	/* targets[i][j] denotes the target state of the j'th transition
	 * from state i. labels[i][j] denotes the label of that
	 * transition. */
	private int targets[][];
	private String labels[][];
	private Expression guards[][];
	private Map<String, Expression> assignments[][];
	private Map<String, Integer> transitions[];
	private final static boolean VERBOSE = false;
	private final static boolean DEBUG = false;

	/** Construct an automaton by reading it from a file.
	 * @param filename The name of the file to read.
	 * @param type The file type of the given file, currently
	 * supported types: aut
	 */
	public Automaton(String filename, String type) throws IOException
	{
		if (VERBOSE)
			System.err.println("New automaton: " + filename);
		switch(type)
		{
			case "aut":
				initState = readAut(filename);
				break;
			case "bcg":
				initState = readBcg(filename);
				break;
			default:
				throw new IllegalArgumentException("Unknown automaton type.");
		}
	}

	/** Generate an explicit-state automaton out of the given LTS,
	 * possibly with a restricted action set.
	 *
	 * The automaton will replicate all behaviour of the LTS,
	 * made explicit as much as possible (e.g., guards will not
	 * refer to local variables anymore, since the variables are now
	 * part of the state).
	 *
	 * If a set of permitted actions is provided, only transitions
	 * will be kept that are either Markovian or contained in the
	 * permitted action set.
	 *
	 * If a set of internal actions is provided, these transitions
	 * may be taken (nondeterministically) without being visible as
	 * a transition, though they may also remain externally visible.
	 * Furthermore, the labels of internal transitions may be
	 * replaced by other internal labels.
	 *
	 * If a set of maximal-progress actions is provided, those
	 * transitions will be assumed to always precede Markovian
	 * actions.
	 *
	 * @param system	The system to make explicit.
	 * @param permitted	The set of permitted actions.
	 * @param internal	The set of internal actions.
	 * @param maxProg	The set of maximal-progress actions.
	 * @param maxMem        The maximal amount of memory to use.
	 */
	public Automaton(LTS system, Set<String> permitted,
	                 Set<String> internal, Set<String> maxProg,
	                 long maxMem)
		throws ModelTooLargeException
	{
		HashMap<LTS.StateWrapperLike, Integer> states = new HashMap<>();
		ArrayDeque<LTS.StateWrapperLike> queue = new ArrayDeque<>();
		queue.add(new LTS.StateWrapper(system.getInitialState()));
		int[] stateHint = new int[system.stateSize()];
		states.put(queue.getFirst(), 0);
		targets = new int[1][];
		labels = new String[1][];
		assignments = createAssignmentArray(1);
		boolean anyHasAssignments = false;
		long memUsed = 0;
		if (DEBUG && internal != null)
			System.err.println("Internal actions: " + internal);
		if (DEBUG && permitted != null)
			System.err.println("Permitted actions: " + permitted);
		while (!queue.isEmpty()) {
			if (targets.length < states.size()) {
				int n = states.size();
				if (n > Integer.MAX_VALUE / 2)
					n = Integer.MAX_VALUE;
				else
					n = 2*n;
				targets = Arrays.copyOf(targets, n);
				labels = Arrays.copyOf(labels, n);
				assignments = Arrays.copyOf(assignments, n);
			}
			LTS.StateWrapperLike cur = queue.poll();
			int[] state = cur.getState(stateHint);
			int num = states.get(cur);
			Collection<LTS.Transition> ts;
			try {
				ts = system.getTransitions(state);
			} catch (NondeterminismException e) {
				throw new UnsupportedOperationException(e);
			}
			if (internal != null)
				ts = cleanupInternal(ts, cur, internal, maxProg);
			targets[num] = new int[ts.size()];
			labels[num] = new String[ts.size()];
			assignments[num] = Arrays.copyOf(assignments[0], ts.size());
			int i = 0;
			for (LTS.Transition t : ts) {
				if (permitted != null
				    && t.label.charAt(0) != 'r'
				    && t.label.charAt(0) != 't'
				    && !permitted.contains(t.label))
				{
					continue;
				}
				if (t.label.equals("r0"))
					continue;
				LTS.StateWrapper tgt = new LTS.StateWrapper(t.target);
				Integer tgtNum = states.get(tgt);
				if (tgtNum == null) {
					LTS.StateWrapperLike wrap = tgt.tryReduce();
					tgtNum = states.size();
					states.put(wrap, tgtNum);
					queue.add(wrap);
					memUsed += 120; /* Conservative estimate of states entry header size */
					memUsed += stateHint.length * 4;
				}
				targets[num][i] = tgtNum;
				labels[num][i] = t.label;
				Expression guard = t.guard;
				try {
					guard.evaluate(Map.of());
				} catch (IllegalArgumentException e) {
					System.err.println("Error evaluating: " + guard);
					throw e;
				}
				if (guard.evaluate(Map.of()) != null) {
					if (guard.evaluate(Map.of()).doubleValue() == 0)
						throw new IllegalArgumentException("Symbolic automaton returned transition with FALSE guard.");
				} else {
					if (guards == null)
						guards = new Expression[num][];
					if (guards.length <= num)
						guards = Arrays.copyOf(guards, num + 1);
					if (guards[num] == null)
						guards[num] = new Expression[ts.size()];
					guards[num][i] = t.guard;
				}
				assignments[num][i] = t.assignments;
				if (t.assignments != null && !t.assignments.isEmpty())
				{
					anyHasAssignments = true;
				}
				i++;
			}
			if (i != labels[num].length) {
				labels[num] = Arrays.copyOf(labels[num], i);
				targets[num] = Arrays.copyOf(targets[num], i);
				assignments[num] = Arrays.copyOf(assignments[num], i);
				if (guards != null && guards[num] != null)
					guards[num] = Arrays.copyOf(guards[num], i);
			}
			memUsed += 48; /* Two array headers, 24 bytes each */
			memUsed += 16; /* Two references to the new arrays */
			memUsed += i * (8 + 4); /* 8 per label, 4 per target */
			if (memUsed > maxMem)
				throw new ModelTooLargeException();
		}
		targets = Arrays.copyOf(targets, states.size());
		states = null;
		labels = Arrays.copyOf(labels, targets.length);
		if (!anyHasAssignments)
			assignments = null;
		else
			assignments = Arrays.copyOf(assignments, targets.length);
		initState = 0;
		int num_states;
		boolean change = false, first = true;
		do {
			num_states = targets.length;
			try {
				change = bisimulationReductionLowMem(internal);
			} catch (OutOfMemoryError f) {
				change = false;
			}
			try {
				if (change || first) {
					if (internal != null && !internal.isEmpty())
						change = tauCollapse(internal);
					else
						change = false;
				} else {
					change = false;
				}
				removeUnreachable();
				if (!change)
					num_states = targets.length;
			} catch (OutOfMemoryError e) {
				num_states = targets.length;
			}
			first = false;
		} while (num_states > targets.length);
		createTransitionArray();
	}

	public Automaton(LTS system) throws ModelTooLargeException
	{
		this(system, null, null, null, Long.MAX_VALUE);
	}

	public static Automaton fromJani(Map<?, ?> janiData,
	                                 Map<String, Number> constants)
		throws ModelTooLargeException
	{
		return new Automaton(SymbolicAutomaton.fromJani(janiData, constants));
	}

	public int hashCode()
	{
		int ret = initState;
		for (int ts[] : targets) {
			for (int t : ts) {
				ret = (ret * 31) + t;
			}
		}
		for (String ls[] : labels) {
			for (String l : ls) {
				ret = (ret * 5) + l.hashCode();
			}
		}
		if (assignments == null)
			return ret;
		for (Map<String, Expression> assignss[] : assignments) {
			for (Map<String, Expression> assigns : assignss) {
				ret = (ret * 3) + assigns.hashCode();
			}
		}
		return ret;
	}

	public boolean equals(Object otherO)
	{
		if (!(otherO instanceof Automaton))
			return false;
		Automaton other = (Automaton)otherO;
		if (other.initState != initState)
			return false;
		if (!Arrays.deepEquals(other.targets, targets))
			return false;
		if (!Arrays.deepEquals(other.labels, labels))
			return false;
		if (!Arrays.deepEquals(other.assignments, assignments))
			return false;
		return true;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void createTransitionArray()
	{
		transitions = (HashMap<String, Integer>[]) new HashMap[labels.length];
		for (int i = labels.length - 1; i >= 0; i--) {
			transitions[i] = new HashMap<String, Integer>();
			for (int j = labels[i].length - 1; j >= 0; j--) {
				if (labels[i][j].charAt(0) == 'r')
					continue;
				if (labels[i][j].charAt(0) == 't')
					continue;
				if (transitions[i].containsKey(labels[i][j])) {
					transitions[i] = null;
					break;
				}
				transitions[i].put(labels[i][j], j);
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Map<String, Expression>[][] createAssignmentArray(int len)
	{
		return (Map<String, Expression>[][]) new Map[len][0];
	}

	/** Create a new automaton by renaming some transitions from an
	 * existing automaton.
	 * @param orig: The automaton from which to rename transitions
	 * @param renames: The set of transition to rename.
	 */
	public Automaton(Automaton orig, Map<String, String> renames)
	{
		boolean anyChanges = false;
		initState = orig.initState;
		targets = orig.targets;
		guards = orig.guards;
		assignments = orig.assignments;
		labels = new String[orig.labels.length][];
		for (int i = 0; i < labels.length; i++) {
			boolean changed = false;
			labels[i] = new String[orig.labels[i].length];
			for (int j = 0; j < labels[i].length; j++) {
				String label = renames.get(orig.labels[i][j]);
				if (label != null) {
					changed = true;
				} else
					label = orig.labels[i][j];
				labels[i][j] = label;
			}
			if (!changed)
				labels[i] = orig.labels[i];
			anyChanges |= changed;
		}
		if (!anyChanges) {
			labels = orig.labels;
			transitions = orig.transitions;
		} else {
			createTransitionArray();
		}
	}

	private int readBcg(String filename) throws IOException
	{
		String[] cmd = new String[] {"bcg_io", filename, "-aldebaran", "-"};
		Process p = Runtime.getRuntime().exec(cmd);
		InputStream i = p.getInputStream();
		int ret = readAutStream(i);
		boolean done = false;
		while (!done) {
			try {
				p.waitFor();
				done = true;
			} catch (InterruptedException e) {
			}
		}
		return ret;
	}

	private Collection<LTS.Transition> cleanupInternal(
			Collection<LTS.Transition> ts, LTS.StateWrapperLike current,
	                Set<String> internal, Set<String> maxProg)
	{
		boolean markovian = false, unstable = false, collapse = false;
		boolean selfLoop = false;
		Set<LTS.StateWrapper> tauReachable = null;
		for (LTS.Transition t : ts) {
			if (maxProg.contains(t.label) && t.guard == ConstantExpression.TRUE) {
				unstable = true;
				if (internal.contains(t.label) && t.assignments == null) {
					if (tauReachable == null)
						tauReachable = new TreeSet<>();
					LTS.StateWrapper wrap;
					wrap = new LTS.StateWrapper(t.target);
					if (tauReachable.contains(wrap))
						collapse = true;
					else if (wrap.equals(current))
						selfLoop = true;
					else
						tauReachable.add(wrap);
				}
			}
			if (t.label.charAt(0) == 'r')
				markovian = true;
			if (t.label.charAt(0) == 't')
				markovian = true;
		}
		if ((!markovian || !unstable) && !collapse && !selfLoop)
			return ts;
		ArrayList<LTS.Transition> ret = new ArrayList<>(ts.size() - 1);
		if (selfLoop) {
			for (LTS.Transition t : ts) {
				LTS.StateWrapper wrap;
				wrap = new LTS.StateWrapper(t.target);
				if (internal.contains(t.label)
					&& t.assignments == null
					&& wrap.equals(current))
				{
					continue;
				}
				ret.add(t);
			}
			ts = ret;
		}
		if ((!markovian || !unstable) && !collapse)
			return ts;
		ret = new ArrayList<>(ts.size() - 1);
		if ((markovian && unstable) || collapse) {
			for (LTS.Transition t : ts) {
				if (t.label.charAt(0) == 'r')
					continue;
				if (t.label.charAt(0) == 't')
					continue;
				if (!internal.contains(t.label)
					|| t.guard != null
					|| t.assignments != null)
				{
					ret.add(t);
					continue;
				}
				LTS.StateWrapper wrap;
				wrap = new LTS.StateWrapper(t.target);
				if (!tauReachable.contains(wrap))
					continue;
				tauReachable.remove(wrap);
				ret.add(t);
			}
		}
		return new LTS.TransitionSet(ret, true);
	}

	public Automaton removeInternalNondet(Map<String, String> renames)
	{
		boolean needsChanges = false;
		TreeSet<String> presentActions = new TreeSet<>();
		Map<String, Set<String>> newNames = new HashMap<>();
		for(int i = 0; i < targets.length; i++) {
			TreeSet<String> stateActions = new TreeSet<>();
			for (int j = 0; j < targets[i].length; j++) {
				String act = labels[i][j];
				if (act.charAt(0) == 'r')
					continue;
				presentActions.add(act);
				if (!stateActions.add(act))
					needsChanges = true;
			}
		}
		if (!needsChanges)
			return this;

		Automaton ret = new Automaton(this, Map.of());
		for(int i = 0; i < targets.length; i++) {
			TreeSet<String> stateActions = new TreeSet<>();
			int k = 0;
			for (int j = 0; j < labels[i].length; j++) {
				String act = labels[i][j];
				if (act.charAt(0) == 'r')
					continue;
				if (stateActions.add(act))
					continue;
				Set<String> acts = newNames.get(act);
				if (acts == null) {
					acts = new TreeSet<>();
					newNames.put(act, acts);
				}
				String newName = null;
				for (String a : acts) {
					if (!stateActions.contains(a)) {
						newName = a;
						break;
					}
				}
				if (newName == null) {
					newName = "ia" + (k++);
					while (presentActions.contains(newName))
						newName = "ia" + (k++);
					acts.add(newName);
				}
				presentActions.add(newName);
				renames.put(newName, labels[i][j]);
				ret.labels[i] = ret.labels[i].clone();
				ret.labels[i][j] = newName;
			}
		}
		ret.createTransitionArray();
		return ret;
	}

	private int readAut(String filename) throws IOException
	{
		return readAutStream(new FileInputStream(filename));
	}

	private int readAutStream(InputStream str) throws IOException
	{
		int ret, nStates, nTrans, i;
		BufferedReader input = new BufferedReader(new InputStreamReader(str));
		String line = input.readLine();
		String parts[];
		if (!line.startsWith("des"))
			System.err.println(line);
		line = line.substring(line.indexOf('(') + 1,
		                      line.lastIndexOf(')'));
		parts = line.split("\\s*,\\s*");
		ret = Integer.parseInt(parts[0]);
		nStates = Integer.parseInt(parts[2]);
		nTrans = Integer.parseInt(parts[1]);
		targets = new int[nStates][0];
		labels = new String[nStates][0];
		for (i = 0; i < nTrans; i++) {
			int first, last, from, to;
			line = input.readLine();
			line = line.substring(line.indexOf('(') + 1,
			                      line.lastIndexOf(')'));
			first = line.indexOf(',');
			last = line.lastIndexOf(',');
			parts[0] = line.substring(0, first).trim();
			parts[1] = line.substring(first + 1, last).trim();
			parts[2] = line.substring(last + 1).trim();
			if (parts[1].charAt(0) == '"') {
				parts[1] = parts[1].substring(1, parts[1].length() - 1);
			}

			from = Integer.parseInt(parts[0]);
			to = Integer.parseInt(parts[2]);
			targets[from] = Arrays.copyOf(targets[from],
			                              targets[from].length + 1);
			labels[from] = Arrays.copyOf(labels[from],
			                             labels[from].length + 1);
			targets[from][targets[from].length - 1] = to;
			String label;
			if (parts[1].startsWith("rate ")) {
				label = 'r' + parts[1].substring(5);
			} else if (parts[1].startsWith("time ")) {
				label = 't' + parts[1].substring(5);
			} else {
				label = 'i' + parts[1];
			}
			labels[from][targets[from].length - 1] = label.intern();
		}
		createTransitionArray();
		return ret;
	}

	private void addDontCaresMutating(String dontCare,
	                                  String notCared,
	                                  Set<String> preserve)
	{
		/* First, identify all states from which we can never
		 * perform a preserved action.
		 * (Technically, we could also check for states from
		 * which we can always perform preserved actions, and
		 * various combinations for different actions, but
		 * I don't have any models exhibiting such behaviour).
		 */
		BitSet dontCareStates = new BitSet(targets.length);
		dontCareStates.set(0, targets.length);
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int i = labels.length - 1; i >= 0; i--) {
				if (!dontCareStates.get(i))
					continue;
				for (String l : labels[i]) {
					if (preserve.contains(l)) {
						dontCareStates.clear(i);
						changed = true;
						break;
					}
				}
				if (!dontCareStates.get(i))
					continue;
				for (int t : targets[i]) {
					if (!dontCareStates.get(t)) {
						dontCareStates.clear(i);
						changed = true;
						break;
					}
				}
			}
		}

		/* Fint or create a target state that can do nothing
		 * (except tell others we have stopped caring).
		 */
		int finalState = dontCareStates.nextSetBit(0);
		if (finalState == -1) {
			finalState = targets.length;
			targets = Arrays.copyOf(targets, finalState+1);
			labels = Arrays.copyOf(labels, finalState + 1);
		}
		/* The final state cannot have guards or
		 * assignments.
		 */
		targets[finalState] = new int[1];
		targets[finalState][0] = finalState;
		labels[finalState] = new String[1];
		labels[finalState][0] = dontCare;

		/* Anything going to any state where we don't care
		 * should go to the final state.
		 */
		for (int i = 0; i < targets.length; i++) {
			for (int j = 0; j < targets[i].length; j++) {
				if (dontCareStates.get(targets[i][j]))
					targets[i][j] = finalState;
			}
		}

		/* Add an unguarded transition from every other state to the
		 * don't care state. */
		for (int i = 0; i < targets.length; i++) {
			if (i == finalState)
				continue;
			int n = targets[i].length;
			targets[i] = Arrays.copyOf(targets[i], n + 1);
			labels[i] = Arrays.copyOf(labels[i], n + 1);
			targets[i][n] = finalState;
			labels[i][n] = notCared;
		}
		createTransitionArray();
	}

	public Automaton addDontCares(String dontCare,
	                              String notCared,
	                              Set<String> preserve)
	{
		Automaton ret;
		try {
			ret = new Automaton(this);
		} catch (ModelTooLargeException e) {
			throw new RuntimeException(e);
		}
		ret.addDontCaresMutating(dontCare, notCared, preserve);
		return ret;
	}

	public Automaton stopCaring(String signal, String dontCareSignal, String stopCaringSignal)
	{
		Automaton ret;
		try {
			ret = new Automaton(this);
		} catch (ModelTooLargeException e) {
			throw new RuntimeException(e);
		}
		int dontCareState = -1;
		for (int i = 0; i < ret.labels.length; i++) {
			if (ret.targets[i].length != 1)
				continue;
			if (ret.targets[i][0] != i)
				continue;
			if (!ret.labels[i][0].equals(dontCareSignal))
				continue;
			dontCareState = i;
			break;
		}
		if (dontCareState == -1)
			throw new IllegalStateException("Attempt to redirect \"don't care\" signal in automaton that doesn't have \"don't care\" state.");
		int targetState = -1;
		for (int i = 0; i < ret.labels.length; i++) {
			if (ret.targets[i].length != 1)
				continue;
			if (ret.targets[i][0] != dontCareState)
				continue;
			if (!ret.labels[i][0].equals(stopCaringSignal))
				continue;
			targetState = i;
			break;
		}
		if (targetState == -1) {
			int n = ret.targets.length;
			ret.targets = Arrays.copyOf(ret.targets, n + 1);
			ret.labels = Arrays.copyOf(ret.labels, n + 1);
			ret.targets[n] = new int[1];
			ret.labels[n] = new String[1];
			ret.targets[n][0] = dontCareState;
			ret.labels[n][0] = stopCaringSignal;
			targetState = n;
		}

		for (int i = 0; i < ret.labels.length; i++) {
			for (int j = 0; j < ret.labels[i].length; j++) {
				if (ret.labels[i][j].equals(signal))
					ret.targets[i][j] = targetState;
			}
		}
		ret.createTransitionArray();
		return ret;
	}

	/**
	 * @return The target of the n'th transition from state 'from',
	 * or -1 if 'from' has fewer than n transitions.
	 */
	public int getTransitionTarget(int from, int n)
	{
		if (targets[from].length <= n)
			return -1;
		return targets[from][n];
	}

	/**
	 * @return The label of the n'th transition from state 'from',
	 * or null if 'from' has fewer than n transitions.
	 */
	public String getTransitionLabel(int from, int n)
	{
		if (labels[from].length <= n)
			return null;
		return labels[from][n];
	}

	/**
	 * @return The guard of the n'th transition from state 'from',
	 * or null if 'from' has fewer than n transitions.
	 */
	public Expression getTransitionGuard(int from, int n)
	{
		if (guards == null || guards.length <= from)
			return null;
		if (guards[from] == null || guards[from].length <= n)
			return null;
		return guards[from][n];
	}

	/**
	 * @return The number of the named transition, or -1 if no such
	 * transition exists.
	 */
	public int getTransitionNum(int from, String transition)
	{
		if (transitions[from] == null)
			throw new UnsupportedOperationException("Internal nondeterminism in context expecting determinised model.");
		Integer r = transitions[from].get(transition);
		if (r == null)
			return -1;
		return r;
	}

	/**
	 * @param includeMarkov Whether to include Markovian
	 * transitions.
	 * @return The set of all actions that occur in this automaton.
	 */
	public Set<String> getAllActions(boolean includeMarkov)
	{
		Set<String> ret = new TreeSet<>();
		int i = 0;
		for (String[] acts : labels) {
			int j = 0;
			for (String act : acts) {
				if (includeMarkov || (act.charAt(0) != 'r' && act.charAt(0) != 't'))
					ret.add(act);
				j++;
			}
			i++;
		}
		return ret;
	}

	public boolean isAlwaysEnabled(String action)
	{
		for (String[] labs : labels) {
			boolean enabled = false;
			for (String label : labs) {
				if (label.equals(action)) {
					enabled = true;
					break;
				}
			}
			if (!enabled)
				return false;
		}
		return true;
	}

	public boolean hasAnyAssignments()
	{
		if (assignments == null)
			return false;
		for (Map<String, Expression> assigns[] : assignments) {
			if (assigns == null)
				continue;
			for (Map<String, Expression> assign : assigns) {
				if (!assign.isEmpty())
					return true;
			}
		}
		return false;
	}

	public Map<String, Expression> getAssignments(int from, int n)
	{
		if (assignments == null)
			return null;
		if (assignments[from].length <= n)
			return null;
		return assignments[from][n];
	}

	public Map<String, Integer> getVarValues(int[] state)
	{
		return new HashMap<String, Integer>();
	}

	public int getVarValue(String var, int[] state)
	{
		throw new IllegalArgumentException("Attempt to read variable '" + var + "' of automaton without variables.");
	}

	/** @return The number of states in this automaton */
	public int getNumStates()
	{
		return labels.length;
	}

	/** @return An approximation of the memory occupied by this
	 * assignment
	 */
	public long getMemUsed()
	{
		long total = 48; /* Headers of labels and assignments */
		if (assignments != null)
			total += 24;
		for (int i = labels.length - 1; i >= 0; i--) {
			/* 4 per target, 8 per label (ref) */
			total += (4 + 8) * labels[i].length;
			total += 48; /* Headers of those arrays */
			if (assignments != null) {
				total += 24; /* Header */
				total += 8 * assignments[i].length;
				/* The actual assignments are shared,
				 * and thus not counted */
			}
		}
		return total;
	}

	public int[] getInitialState()
	{
		return new int[]{0};
	}

	public TreeSet<LTS.Transition> getTransitions(int[] from)
	{
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		int target, src = from[0];
		for (int i = 0; i < targets[src].length; i++) {
			target = targets[src][i];
			String label = labels[src][i];
			Expression guard = ConstantExpression.TRUE;
			if (guards != null
			    && guards.length > src
			    && guards[src] != null
			    && guards[src].length > i
			    && guards[src][i] != null)
			{
				guard = guards[src][i];
			}
			Map<String, Expression> assigns = Map.of();
			if (assignments != null
			    && assignments.length > src
			    && assignments[src].length > i)
			{
				assigns = assignments[src][i];
			}
			ret.add(new LTS.Transition(label, new int[]{target},
			                           guard,
			                           assigns));
		}
		return ret;
	}

	/** Return a reduced automaton that can only execute the
	 * specified labels (in addition to Markovian rates).
	 */
	public Automaton trim(Set<String> keep) {
		boolean anyChange = false;
		boolean needsChange[] = new boolean[labels.length];
		for (int i = 0; i < labels.length; i++) {
			for (int j = 0; j < labels[i].length; j++) {
				if (labels[i][j].charAt(0) == 'r')
					continue;
				if (labels[i][j].charAt(0) == 't')
					continue;
				if (!keep.contains(labels[i][j])) {
					needsChange[i] = true;
					anyChange = true;
					break;
				}
			}
		}
		if (!anyChange)
			return this;
		try {
			return new Automaton(this, keep, null, null, Long.MAX_VALUE);
		} catch (ModelTooLargeException e) {
			throw new RuntimeException(e);
		}
	}

	private void removeUnreachable() {
		if (initState != 0)
			return;
		int newNumber[] = new int[targets.length];
		Arrays.fill(newNumber, Integer.MIN_VALUE);
		boolean stable = false;
		int nextNumber = 1;
		newNumber[initState] = 0;

		for (int i = 0; i < targets[initState].length; i++) {
			int t = targets[initState][i];
			if (newNumber[t] == Integer.MIN_VALUE) {
				targets[initState][i] = nextNumber;
				newNumber[t] = -(nextNumber++);
			} else {
				targets[initState][i] = -newNumber[t];
			}
		}
		while (!stable) {
			stable = true;
			for (int i = 0; i < targets.length; i++) {
				if (newNumber[i] >= 0 || newNumber[i] == Integer.MIN_VALUE)
					continue;
				stable = false;
				newNumber[i] = -newNumber[i];
				int ts[] = targets[i];
				for (int j = ts.length - 1; j >= 0; j--) {
					int t = ts[j];
					if (newNumber[t] == Integer.MIN_VALUE) {
						ts[j] = nextNumber;
						newNumber[t] = -(nextNumber++);
					} else {
						ts[j] = Math.abs(newNumber[t]);
					}
				}
			}
		}
		int newTs[][] = new int[nextNumber][];
		String newLs[][] = new String[nextNumber][];
		for (int i = targets.length - 1; i >= 0; i--) {
			if (newNumber[i] == Integer.MIN_VALUE)
				continue;
			newTs[newNumber[i]] = targets[i];
			newLs[newNumber[i]] = labels[i];
		}
		this.targets = newTs;
		this.labels = newLs;
	}

	private static class BitSetIterator implements PrimitiveIterator.OfInt
	{
		private BitSet set;
		private int next, prev = -1;

		public BitSetIterator(BitSet set) {
			this.set = set;
			next = set.nextSetBit(0);
		}

		public boolean hasNext() {
			return next != -1;
		}

		public Integer next() {
			Integer ret = next;
			prev = next;
			next = set.nextSetBit(next + 1);
			return ret;
		}

		public int nextInt() {
			Integer ret = next;
			prev = next;
			next = set.nextSetBit(next + 1);
			return ret;
		}

		public void remove() {
			set.clear(prev);
		}
	}

	private void collapseSameMarkov(Set<String> internal) {
		Set<List<Object>> nonMarkov = new HashSet<>();
		for (int i = labels.length - 1; i >= 0; i--) {
			nonMarkov.clear();
			int[] ts = targets[i];
			String[] ls = labels[i];
			/* Remove self-loops and non-Markov dupes */
			int length = ts.length;
			for (int j = ts.length - 1; j >= 0; j--) {
				if (ls[j].charAt(0) != 'r') {
					List<Object> t;
					if (!internal.contains(ls[j]))
						t = List.of(ts[j], ls[j]);
					else
						t = List.of(ts[j]);
					if ((ts[j] != i
					     || !internal.contains(ls[j]))
					    && nonMarkov.add(t))
						continue;
				} else {
					if (ts[j] != i)
						continue;
				}
				length--;
				ts[j] = ts[length];
				ls[j] = ls[length];
			}
			HashMap<Integer, Integer> idxs = new HashMap<>();
			int offset = 0;
			for (int j = 0; j < length; j++) {
				ls[j - offset] = ls[j];
				ts[j - offset] = ts[j];
				if (ls[j].charAt(0) != 'r')
					continue;
				Integer prev = idxs.get(ts[j]);
				if (prev == null) {
					idxs.put(ts[j], j - offset);
					continue;
				}
				String prevL = ls[prev], newL;
				newL = MarkovReducedLTS.addLabels(prevL, ls[j]);
				ls[prev] = newL;
				offset++;
			}
			if (offset != 0 || length != ts.length) {
				targets[i] = Arrays.copyOf(ts, length - offset);
				labels[i] = Arrays.copyOf(ls, length - offset);
			}
		}
	}

	private int partitionLowMem(int[] pre, int[] post, BitSet done,
	                            Set<String> internal)
	{
		HashMap<List<Object>, Integer> signatures = new HashMap<>();
		BitSet newDone = new BitSet();
		int nStates = labels.length;
		for (int i = 0; i < nStates; i++) {
			if (done.get(pre[i])) {
				List<Object> bList = List.of(pre[i]);
				Integer block = signatures.get(bList);
				if (block == null) {
					block = signatures.size();
					signatures.put(bList, block);
				}
				post[i] = block;
				newDone.set(block);
				continue;
			}
			boolean notDone = false;
			Map<Integer, Set<String>> signature = new HashMap<>();
			Map<Integer, BigDecimal> rates = new HashMap<>();
			for (int l = labels[i].length - 1; l >= 0; l--) {
				int block = targets[i][l];
				block = pre[block];
				String label = labels[i][l];
				notDone |= !done.get(block);
				if (label.charAt(0) == 'r') {
					if (pre[i] == block)
						continue;
					label = label.substring(1);
					BigDecimal r = new BigDecimal(label);
					BigDecimal rate = rates.get(block);
					if (rate == null)
						rate = r;
					else
						rate = rate.add(r);
					rates.put(block, rate);
					continue;
				}
				if (internal.contains(label)) {
					if (pre[i] == block)
						continue;
					label = "";
				}
				Set<String> transitions = signature.get(block);
				if (transitions == null) {
					transitions = new TreeSet<>();
					signature.put(block, transitions);
				}
				transitions.add(label);
			}
			for (Integer t : rates.keySet()) {
				BigDecimal rate = rates.get(t);
				if (rate != BigDecimal.ZERO) {
					rate = rate.stripTrailingZeros();
					rates.replace(t, rate);
				}
			}
			List<Object> compressed = new ArrayList<>();
			Set<Integer> targets = new TreeSet<>();
			targets.addAll(signature.keySet());
			targets.addAll(rates.keySet());
			int lastBlock = 0;
			for (Integer j : targets) {
				compressed.add(j - lastBlock - 128);
				lastBlock = j;
				Set<String> ls = signature.get(j);
				if (ls != null)
					compressed.addAll(ls);
				BigDecimal rate = rates.get(j);
				if (rate != null) {
					rates.remove(j);
					compressed.add(rate);
				}
			}
			Integer newBlock = signatures.get(compressed);
			if (newBlock == null) {
				newBlock = signatures.size();
				compressed = List.copyOf(compressed);
				signatures.put(compressed, newBlock);
				newDone.set(newBlock);
			} else {
				if (notDone)
					newDone.clear(newBlock);
			}
			post[i] = newBlock;
		}
		done.clear();
		done.or(newDone);
		return signatures.size();
	}

	private boolean bisimulationReductionLowMem(Set<String> internal) {
		if (targets.length == 1)
			return false;
		if (assignments != null || guards != null)
			return false;
		if (internal == null)
			return bisimulationReductionLowMem(Set.of());
		if (VERBOSE) {
			System.err.println("LMBisimulation reducing from " + targets.length + " states (internal actions: " + internal + ")");
			if (DEBUG)
				System.err.println(toString());
		}
		int blockNumsPre[] = new int[labels.length];
		int blockNumsPost[] = new int[labels.length];
		BitSet done = new BitSet();
		int numPreBlocks = 0, numPostBlocks = labels.length;
		while (numPreBlocks != numPostBlocks) {
			numPreBlocks = numPostBlocks;
			int[] tmp = blockNumsPost;
			blockNumsPost = blockNumsPre;
			blockNumsPre = tmp;
			numPostBlocks = partitionLowMem(blockNumsPre, blockNumsPost, done, internal);
			if (VERBOSE)
				System.err.println("Currently have " + numPostBlocks + " blocks");
		}
		done = null;

		/* Implementation node: Since partitions are assigned
		 * sequentially, we will never visit a state with a
		 * block number higher than the state number.
		 */
		HashMap<Integer, Integer> renames = new HashMap<>();
		for (int i = 0; i < targets.length; i++) {
			int block = blockNumsPost[i];
			blockNumsPre[i] = 0;
			if (blockNumsPre[block] != 0)
				renames.put(i, blockNumsPost[block]);
			else
				blockNumsPost[block] = i;
			blockNumsPre[block]++;
		}
		if (renames.isEmpty()) {
			if (DEBUG)
				System.err.println("No change");
			return false;
		}
		if (DEBUG)
			System.err.println("Renames: " + renames);
		blockNumsPre = blockNumsPost = null;

		for (int s = targets.length - 1; s >= 0; s--) {
			for (int t = targets[s].length - 1; t >= 0; t--) {
				Integer to = renames.get(targets[s][t]);
				if (to != null)
					targets[s][t] = to;
			}
		}
		collapseSameMarkov(internal);
		return true;
	}

	private Set<LTS.Transition> getTransitionsExcept(int from,
	                                                 Set<Integer> except,
	                                                 Set<String> internal)
	{
		ArrayList<LTS.Transition> ret = new ArrayList<>();
		for (int i = targets[from].length - 1; i >= 0; i--) {
			String label = labels[from][i];
			if ((targets[from][i] == from
			          || except.contains(targets[from][i]))
			    && (label.charAt(0) == 'r'
			          || internal.contains(label)))
			{
				Map<?, ?> assigns = getAssignments(from, i);
				if (assigns == null || assigns.isEmpty())
					continue;
			}
			int[] target = new int[]{targets[from][i]};
			if (label == null)
				throw new NullPointerException(from + " -> " + targets[from][i] + " (" + i + ")");
			Expression guard = getTransitionGuard(from, i);
			Map<String, Expression> asgn = getAssignments(from, i);
			ret.add(new LTS.Transition(label, target, guard, asgn));
		}
		return new LTS.TransitionSet(ret, true);
	}

	private void replaceTargets(int from, int t, Set<Integer> newTgts)
	{
		int toAdd = newTgts.size() - 1;
		Iterator<Integer> it = newTgts.iterator();
		targets[from][t] = it.next();
		if (toAdd == 0)
			return;
		int oldSize = targets[from].length;
		int newSize = oldSize + toAdd;
		int[] newTargets = Arrays.copyOf(targets[from], newSize);
		targets[from] = newTargets;
		String[] newLabels = Arrays.copyOf(labels[from], newSize);
		String label = newLabels[t];
		labels[from] = newLabels;
		Expression guard = null;
		Expression[] newGuards = null;
		Map<String, Expression> assigns = null;
		Map<String, Expression>[] newAssigns = null;
		if (guards != null && guards.length > from) {
			Expression gs[] = guards[from];
			if (gs != null && gs.length >= t) {
				guard = gs[t];
				newGuards = Arrays.copyOf(gs, newSize);
				guards[from] = newGuards;
			}
		}
		if (assignments != null && assignments.length > from) {
			Map<String, Expression> as[] = assignments[from];
			if (as != null && as.length >= t) {
				assigns = as[t];
				newAssigns = Arrays.copyOf(as, newSize);
				assignments[from] = newAssigns;
			}
		}
		int i = oldSize;
		while (it.hasNext()) {
			newTargets[i] = it.next();
			newLabels[i] = label;
			if (assigns != null)
				newAssigns[i] = assigns;
			if (guard != null)
				newGuards[i] = guard;
			i++;
		}
	}

	private boolean tauCollapse(Set<String> internals)
	{
		boolean visible[] = new boolean[targets.length];
		boolean any = false;
		ArrayList<Set<Integer>> tauCollapsible = new ArrayList<>(targets.length);
		int emptyTarget = -1;
		if (VERBOSE) {
			System.err.println("Collapsing under " + internals);
			if (DEBUG)
				System.err.println(toString());
		}
stateLoop:
		for (int i = 0; i < targets.length; i++) {
			if (targets[i].length == 0) {
				Set<Integer> tgt = null;
				if (emptyTarget == -1) {
					emptyTarget = i;
				} else {
					tgt = new TreeSet<Integer>();
					tgt.add(emptyTarget);
				}
				tauCollapsible.add(tgt);
				continue;
			}
			tauCollapsible.add(null);
			for (int j = labels[i].length - 1; j >= 0; j--) {
				if (labels[i][j].charAt(0) != 'i') {
					visible[i] = true;
					continue stateLoop;
				}
				if (!internals.contains(labels[i][j])) {
					visible[i] = true;
					continue stateLoop;
				}
				if (getTransitionGuard(i, j) != null) {
					visible[i] = true;
					continue stateLoop;
				}
				Map<String, Expression> assigns
					= getAssignments(i, j);
				if (assigns != null && !assigns.isEmpty()) {
					visible[i] = true;
					continue stateLoop;
				}
			}
			TreeSet<Integer> reach = new TreeSet<Integer>();
			tauCollapsible.set(i, reach);
			any = true;
			for (int j = labels[i].length - 1; j >= 0; j--)
				reach.add(targets[i][j]);
		}
		if (!any) {
			if (DEBUG)
				System.err.println("No effect");
			return false;
		}
		if (DEBUG)
			System.err.println("Tau-collapsible: " + tauCollapsible);
		boolean change = true;
		while (change) {
			change = false;
			for (int i = 0; i < targets.length; i++) {
				if (visible[i])
					continue;
				Set<Integer> reach = tauCollapsible.get(i);
				if (reach == null)
					continue;
				Iterator<Integer> it = reach.iterator();
				while (it.hasNext()) {
					int t = it.next();
					Set<Integer> tgt = tauCollapsible.get(t);
					if (tgt == null)
						continue;
					if (reach.addAll(tgt)) {
						change = true;
						it = reach.iterator();
					}
				}
				it = reach.iterator();
				while (it.hasNext()) {
					int t = it.next();
					if (!visible[t])
						it.remove();
				}
				if (reach.isEmpty())
					tauCollapsible.set(i, null);
			}
		}
		if (DEBUG)
			System.err.println("Tau-closed: " + tauCollapsible);
		for (int i = targets.length - 1; i >= 0; i--) {
			for (int j = targets[i].length - 1; j >= 0; j--) {
				int t = targets[i][j];
				Set<Integer> newTgts;
				newTgts = tauCollapsible.get(t);
				if (newTgts == null)
					continue;
				String label = labels[i][j];
				if (label.charAt(0) == 'r') {
					if (newTgts.size() == 1) {
						t = newTgts.iterator().next();
						targets[i][j] = t;
						any = true;
					}
				} else if (label.charAt(0) == 'i') {
					replaceTargets(i, j, newTgts);
					any = true;
				} else {
					System.err.println("Unknown transition type for tau-collapse: " + label);
				}
			}
		}
		if (!visible[initState] && tauCollapsible.get(initState) != null) {
			Set<Integer> init = tauCollapsible.get(initState);
			if (init.size() == 1) {
				int to = init.iterator().next();
				targets[initState] = targets[to].clone();
				labels[initState] = labels[to].clone();
				if (guards != null) {
					if (guards.length > to) {
						if (guards.length <= initState)
							guards = Arrays.copyOf(guards, initState + 1);
						guards[initState] = guards[to].clone();
					} else if (guards.length > initState)
						guards[initState] = null;
				}
				if (assignments != null) {
					if (assignments.length > to) {
						if (assignments.length <= initState)
							assignments = Arrays.copyOf(assignments, initState + 1);
						assignments[initState] = assignments[to].clone();
					} else if (assignments.length > initState)
						assignments[initState] = null;
				}
			}
		}
		if (DEBUG) {
			System.err.println("Result:");
			System.err.println(toString());
		}
		return true;
	}

	public int stateSize()
	{
		return 1;
	}

	public String toString()
	{
		StringBuilder ret = new StringBuilder();
		ret.append(String.format("Number of states: %d\n", getNumStates()));
		ret.append(String.format("Initial state: %d\n", initState));
		for (int i = 0; i < getNumStates(); i++) {
			for (int j = 0; getTransitionTarget(i, j) > -1; j++) {
				Expression guard = getTransitionGuard(i, j);
				Map<String, Expression> assigns = getAssignments(i, j);
				ret.append(String.format("%5d %s %5d (%s)%s\n",
						i,
						guard == null ? "--->" : ("--" + guard + "->"),
						getTransitionTarget(i, j),
						getTransitionLabel(i, j),
						assigns == null ? "" : (" [" + assigns + "]")
						));
			}
		}
		return ret.toString();
	}

	public void printJaniAutomaton(String name, PrintStream out)
	{
		out.println("\t{\"name\":\""+name+"\",");
		out.print  ("\t \"locations\":[");
		for (int i = 0; i < targets.length; i++) {
			if (i > 0)
				out.print(",");
			out.print("{\"name\":\"l"+i+"\"}");
		}
		out.println("],"); /* End of locations */
		out.println("\t \"initial-locations\":[\"l"+initState+"\"],");
		out.println("\t \"edges\":[");
		boolean firstEdge = true;
		for (int i = 0; i < targets.length; i++) {
			for (int j = 0; j < targets[i].length; j++) {
				if (!firstEdge)
					out.println(",");
				firstEdge = false;
				out.println("\t\t{\"location\":\"l"+i+"\",");
				if (assignments == null || assignments[i] == null || assignments[i].length <= j || assignments[i][j] == null) {
					out.println("\t\t \"destinations\":[{\"location\":\"l"+targets[i][j]+"\"}],");
				} else {
					out.println("\t\t \"destinations\":[{");
					out.println("\t\t\t\"location\": \"l" + targets[i][j] + "\",");
					out.println("\t\t\t\"assignments\": [");
					boolean first = true;
					for (String var : assignments[i][j].keySet()) {
						Expression val = assignments[i][j].get(var);
						if (!first)
							out.println(",");
						first = false;
						out.print("\t\t\t\t{\"ref\": \"" + var + "\", \"value\": ");
						val.writeJani(out, 5);
						out.print("}");
					}
					out.println();
					out.println("\t\t\t]");
					out.println("\t\t }],");
				}
				if (labels[i][j].charAt(0) == 'r') {
					out.println("\t\t \"rate\":{\"exp\":"+labels[i][j].substring(1)+"}");
				} else {
					out.println("\t\t \"action\":\""+labels[i][j].substring(1)+"\"");
				}
				out.print("\t\t}");
			}
		}
		out.print("\n\t]}");
	}
}
