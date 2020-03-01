package nl.utwente.ewi.fmt.EXPRES.expression;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

import models.StateSpace;

public class BinaryExpression extends Expression
{
	public static enum Operator {
	       EQUALS("=", true),
	       NOT_EQUALS("≠", true),
	       LESS("<", true),
	       LESS_OR_EQUAL("≤", true),
	       GREATER(">", true),
	       GREATER_OR_EQUAL("≥", true),
	       AND("∧", true),
	       OR("∨", true),
	       XOR("xor", true),
	       ADD("+", false),
	       SUBTRACT("-", false),
	       DIVIDE("/", false),
	       MULTIPLY("*", false),
	       POWER("pow", false),
	       MINIMUM("min", false),
	       MAXIMUM("max", false),
		       ;

	       public final String symbol;
	       public final boolean returnsBoolean;
	       Operator(String symb, boolean ret) {
		       symbol = symb;
		       returnsBoolean = ret;
	       }
	}

	public final Operator op;
	public final Expression left, right;
	private Set<String> variables;

	public BinaryExpression(Operator op, Expression left, Expression right)
	{
		if (op == Operator.GREATER) {
			this.op = Operator.LESS;
			this.left = right;
			this.right = left;
		} else if (op == Operator.GREATER_OR_EQUAL) {
			this.op = Operator.LESS_OR_EQUAL;
			this.left = right;
			this.right = left;
		} else {
			this.op = op;
			this.left = left;
			this.right = right;
		}
		TreeSet<String> vs = new TreeSet<>(left.getReferencedVariables());
		vs.addAll(right.getReferencedVariables());
		variables = Set.copyOf(vs);
	}

	public Set<String> getReferencedVariables() {
		return variables;
	}

	private static long checkedInteger(Number v)
	{
		if ((v instanceof Long) || (v instanceof Integer))
			return v.longValue();
		throw new IllegalArgumentException("Expected integer type, found : " + v);
	}

	public Number evaluate(Map<String, ? extends Number> valuation) {
		if (op == Operator.AND) {
			Number l = left.evaluate(valuation);
			if (l != null && l.doubleValue() == 0)
				return 0;
			Number r = right.evaluate(valuation);
			if (r != null && r.doubleValue() == 0)
				return 0;
			if (l != null && r != null)
				return 1;
			return null;
		} else if (op == Operator.OR) {
			Number l = left.evaluate(valuation);
			if (l != null && l.doubleValue() != 0)
				return 1;
			Number r = right.evaluate(valuation);
			if (r != null && r.doubleValue() != 0)
				return 1;
			if (l != null && r != null)
				return 0;
			return null;
		}
		Number l = left.evaluate(valuation);
		Number r = right.evaluate(valuation);
		Number vL = null, vR = null;
		if (l != null && (l instanceof Long || l instanceof Integer))
			vL = l;
		if (r != null && (r instanceof Long || r instanceof Integer))
			vR = r;
		Boolean boolL = null, boolR = null;
		if (l != null)
			boolL = l.doubleValue() != 0;
		if (r != null)
			boolR = r.doubleValue() != 0;

		Boolean bRet = null;
		switch (op) {
			case EQUALS:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() == vR.longValue();
				break;
			case NOT_EQUALS:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() != vR.longValue();
				break;
			case LESS:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() < vR.longValue();
				break;
			case LESS_OR_EQUAL:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() <= vR.longValue();
				break;
			case ADD:
				if (vL == null || vR == null) {
					if (l == null || r == null)
						return null;
					return l.doubleValue() + r.doubleValue();
				}
				return vL.longValue() + vR.longValue();
			case SUBTRACT:
				if (vL == null || vR == null) {
					if (l == null || r == null)
						return null;
					return l.doubleValue() - r.doubleValue();
				}
				return vL.longValue() - vR.longValue();
			case DIVIDE:
				if (l == null || r == null)
					return null;
				return l.doubleValue() / r.doubleValue();
			case MULTIPLY:
				if (l == null || r == null)
					return null;
				if (l instanceof Double || r instanceof Double){
					return l.doubleValue() * r.doubleValue();
				} else {
					return Math.multiplyExact(l.longValue(), r.longValue());
				}
			case POWER:
				if (l == null || r == null)
					return null;
				return Math.pow(l.doubleValue(), r.doubleValue());
			case MINIMUM:
				if (l == null || r == null)
					return null;
				if (l.doubleValue() < r.doubleValue())
					return l;
				if (l.doubleValue() > r.doubleValue())
					return r;
				if (l.longValue() < r.longValue())
					return l;
				if (l.longValue() > r.longValue())
					return r;
				return l;
			case MAXIMUM:
				if (l == null || r == null)
					return null;
				if (l.doubleValue() < r.doubleValue())
					return r;
				if (l.doubleValue() > r.doubleValue())
					return l;
				if (l.longValue() < r.longValue())
					return r;
				if (l.longValue() > r.longValue())
					return l;
				return l;
			case XOR:
				if (boolL == null || boolR == null)
					return null;
				bRet = boolL ^ boolR;
				break;
			default:
				throw new UnsupportedOperationException("Unknown operator: " + op.symbol);
		}
		return bRet ? 1 : 0;
	}

	public void writeJani(PrintStream out, int indent) {
		out.print("{\"op\": \"");
		out.print(op.symbol);
		out.print("\", \"left\": ");
		left.writeJani(out, indent);
		out.print(", \"right\": ");
		right.writeJani(out, indent);
		out.print("}");
	}

	public int hashCode() {
		return op.symbol.hashCode()
		       + 31 * (left.hashCode()
		               + 31 * right.hashCode());
	}

	public boolean equals(Object other) {
		Expression us = simplify(Map.of());
		if (us != this)
			return us.equals(other);
		if (!(other instanceof Expression))
			return false;
		Expression them = ((Expression)other).simplify(Map.of());
		if (!(them instanceof BinaryExpression))
			return false;
		BinaryExpression o = (BinaryExpression)them;
		return (op == o.op)
		       && left.equals(o.left)
		       && right.equals(o.right);
	}

	public String toString() {
		return '(' + left.toString() + ')' + op.symbol + '(' + right.toString() + ')';
	}

	public Expression booleanExpression() {
		if (op.returnsBoolean)
			return this;
		return super.booleanExpression();
	}

	@Override
	public Expression simplify(Map<?, ? extends Number> assumptions) {
		Expression ret = super.simplify(assumptions);
		if (ret != this)
			return ret.simplify(assumptions);
		Expression simplerL = left.simplify(assumptions);
		Expression simplerR = right.simplify(assumptions);
		Number constL = simplerL.evaluate(Map.of());
		Number constR = simplerR.evaluate(Map.of());
		if (constL != null && constR != null) {
			Number c = new BinaryExpression(op, simplerL, simplerR).evaluate(Map.of());
			return new ConstantExpression(c);
		}
		if (constL != null || constR != null) {
			switch (op) {
			case AND:
				if (constL != null) {
					if (constL.doubleValue() == 0)
						return new ConstantExpression(0);
					return simplerR;
				}
				if (constR != null) {
					if (constR.doubleValue() == 0)
						return new ConstantExpression(0);
					return simplerL;
				}
				break;
			case OR:
				if (constL != null) {
					if (constL.doubleValue() != 0)
						return new ConstantExpression(1);
					return simplerR;
				}
				if (constR != null) {
					if (constR.doubleValue() != 0)
						return new ConstantExpression(1);
					return simplerL;
				}
				break;
			case NOT_EQUALS:
			case ADD:
			case SUBTRACT:
			case XOR:
				if (constL != null && constL.doubleValue() == 0)
					return simplerR;
				if (constR != null && constR.doubleValue() == 0)
					return simplerL;
				break;
			}
		}
		if (simplerL != left || simplerR != right)
			return new BinaryExpression(op, simplerL, simplerR).simplify(Map.of());
		return this;
	}

	@Override
	public BinaryExpression renameVars(Map<String, String> renames) {
		return new BinaryExpression(op, left.renameVars(renames),
		                            right.renameVars(renames));
	}

	public Map<Expression, Number> subAssumptions(Number value)
	{
		if (op == Operator.AND && value.doubleValue() == 1) {
			Map<Expression, Number> ret = new HashMap<>();
			ret.put(this, value);
			if (left instanceof BinaryExpression) {
				BinaryExpression bin = (BinaryExpression)left;
				if (bin.op.returnsBoolean) {
					ret.put(left, 1);
					ret.putAll(left.subAssumptions(1));
				}
			}
			if (right instanceof BinaryExpression) {
				BinaryExpression bin = (BinaryExpression)right;
				if (bin.op.returnsBoolean) {
					ret.put(right, 1);
					ret.putAll(right.subAssumptions(1));
				}
			}
			return ret;
		} else if (op == Operator.OR && value.doubleValue() == 0) {
			Map<Expression, Number> ret = new HashMap<>();
			ret.put(this, value);
			ret.put(left, 0);
			ret.putAll(left.subAssumptions(0));
			ret.put(right, 0);
			ret.putAll(right.subAssumptions(0));
			return ret;
		}
		return super.subAssumptions(value);
	}
}
