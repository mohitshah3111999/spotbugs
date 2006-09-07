/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003,2004 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs;

import java.util.Iterator;
import java.util.NoSuchElementException;

import edu.umd.cs.findbugs.model.ClassNameRewriter;
import edu.umd.cs.findbugs.model.ClassNameRewriterUtil;
import edu.umd.cs.findbugs.model.IdentityClassNameRewriter;

/**
 * Compare bug instances by only those criteria which we would expect to
 * remain constant between versions.
 */
public class VersionInsensitiveBugComparator implements WarningComparator {
	
	private ClassNameRewriter classNameRewriter = IdentityClassNameRewriter.instance();
	
	private boolean exactBugPatternMatch = true;
	
	private boolean comparePriorities = false;
	public VersionInsensitiveBugComparator() {
	}
	
	public void setClassNameRewriter(ClassNameRewriter classNameRewriter) {
		this.classNameRewriter = classNameRewriter; 
	}
	public void setComparePriorities(boolean b) {
		comparePriorities = b;
	}

	/**
	 * Wrapper for BugAnnotation iterators, which filters out
	 * annotations we don't care about.
	 */
	private class FilteringAnnotationIterator implements Iterator<BugAnnotation> {
		private Iterator<BugAnnotation> iter;
		private BugAnnotation next;

		public FilteringAnnotationIterator(Iterator<BugAnnotation> iter) {
			this.iter = iter;
			this.next = null;
		}

		public boolean hasNext() {
			findNext();
			return next != null;
		}

		public BugAnnotation next() {
			findNext();
			if (next == null)
				throw new NoSuchElementException();
			BugAnnotation result = next;
			next = null;
			return result;
		}

		public void remove() {
			throw new UnsupportedOperationException();
		}

		private void findNext() {
			while (next == null) {
				if (!iter.hasNext())
					break;
				BugAnnotation candidate = iter.next();
				if (!isBoring(candidate)) {
					next = candidate;
					break;
				}
			}
		}

	}

	private boolean isBoring(BugAnnotation annotation) {
		// We ignore int annotations.
		if (annotation.getClass() == IntAnnotation.class)
			return true;

		// Ignore source line annotations that aren't
		// either default or unknown.
		if (annotation instanceof SourceLineAnnotation) {
			SourceLineAnnotation srcLine = (SourceLineAnnotation) annotation;
			String description = srcLine.getDescription();
			return !(description.equals("SOURCE_LINE_DEFAULT") || description.equals("SOURCE_LINE_UNKNOWN"));
		}

		return false;
	}

	private static int compareNullElements(Object a, Object b) {
		if (a != null)
			return 1;
		else if (b != null)
			return -1;
		else
			return 0;
	}
	
	private static String getCode(String pattern) {
		int sep = pattern.indexOf('_');
		if (sep < 0)
			return "";
		return pattern.substring(0, sep);
	}

	public int compare(BugInstance lhs, BugInstance rhs) {
		// Attributes of BugInstance.
		// Compare abbreviation 
		// Compare class and method annotations (ignoring line numbers).
		// Compare field annotations.

		int cmp;
		
		BugPattern lhsPattern = lhs.getBugPattern();
		BugPattern rhsPattern = rhs.getBugPattern();
		
		if (lhsPattern == null || rhsPattern == null) {
			// One of the patterns is missing.
			// However, we can still accurately match by abbrev (usually) by comparing
			// the part of the type before the first '_' character.
			// This is almost always equivalent to the abbrev.
			
			String lhsCode = getCode(lhs.getType());
			String rhsCode = getCode(rhs.getType());
			
			if ((cmp = lhsCode.compareTo(rhsCode)) != 0) {
				return cmp;
			}
		} else {
			// Compare by abbrev instead of type. The specific bug type can change
			// (e.g., "definitely null" to "null on simple path").  Also, we often
			// change bug pattern types from one version of FindBugs to the next.
			//
			// Source line and field name are still matched precisely, so this shouldn't
			// cause loss of precision.
			if ((cmp = lhsPattern.getAbbrev().compareTo(rhsPattern.getAbbrev())) != 0)
				return cmp;
			if (isExactBugPatternMatch() && (cmp = lhsPattern.getType().compareTo(rhsPattern.getType())) != 0)
				return cmp;
		}
		



		if (comparePriorities) {
			cmp = lhs.getPriority() - rhs.getPriority();
			if (cmp != 0) return cmp;
		}


		Iterator<BugAnnotation> lhsIter = new FilteringAnnotationIterator(lhs.annotationIterator());
		Iterator<BugAnnotation> rhsIter = new FilteringAnnotationIterator(rhs.annotationIterator());

		while (lhsIter.hasNext() && rhsIter.hasNext()) {
			BugAnnotation lhsAnnotation = lhsIter.next();
			BugAnnotation rhsAnnotation = rhsIter.next();

			// Different annotation types obviously cannot be equal,
			// so just compare by class name.
			if (lhsAnnotation.getClass() != rhsAnnotation.getClass())
				return lhsAnnotation.getClass().getName().compareTo(rhsAnnotation.getClass().getName());

			if (lhsAnnotation.getClass() == ClassAnnotation.class) {
				// ClassAnnotations should have their class names rewritten to
				// handle moved and renamed classes.
				
				String lhsClassName = classNameRewriter.rewriteClassName(
						((ClassAnnotation)lhsAnnotation).getClassName());
				String rhsClassName = classNameRewriter.rewriteClassName(
						((ClassAnnotation)rhsAnnotation).getClassName());
				
				return lhsClassName.compareTo(rhsClassName);
				
			} else if(lhsAnnotation.getClass() == MethodAnnotation.class ) {
				// Rewrite class names in MethodAnnotations
				MethodAnnotation lhsMethod = ClassNameRewriterUtil.convertMethodAnnotation(
						classNameRewriter, (MethodAnnotation) lhsAnnotation);
				MethodAnnotation rhsMethod = ClassNameRewriterUtil.convertMethodAnnotation(
						classNameRewriter, (MethodAnnotation) rhsAnnotation);
				
				cmp = lhsMethod.compareTo(rhsMethod);
				if (cmp != 0)
					return cmp;
				
			} else if(lhsAnnotation.getClass() == FieldAnnotation.class) {
				// Rewrite class names in FieldAnnotations
				FieldAnnotation lhsField = ClassNameRewriterUtil.convertFieldAnnotation(
						classNameRewriter, (FieldAnnotation) lhsAnnotation);
				FieldAnnotation rhsField = ClassNameRewriterUtil.convertFieldAnnotation(
						classNameRewriter, (FieldAnnotation) rhsAnnotation);
				
				cmp = lhsField.compareTo(rhsField);
				if (cmp != 0)
					return cmp;
			} else if (lhsAnnotation.getClass() == SourceLineAnnotation.class) {
				// We assume that source lines may change, but source files
				// and bytecode offsets will not.
				SourceLineAnnotation lhsSource = (SourceLineAnnotation) lhsAnnotation;
				SourceLineAnnotation rhsSource = (SourceLineAnnotation) rhsAnnotation;
				cmp = lhsSource.getSourceFile().compareTo(rhsSource.getSourceFile());
				if (cmp != 0) return cmp;
				cmp = lhsSource.getStartBytecode() - rhsSource.getStartBytecode();
				if (cmp != 0) return cmp;
				cmp = lhsSource.getEndBytecode() - rhsSource.getEndBytecode();
				if (cmp != 0) return cmp;
			} else if (isBoring(lhsAnnotation)) {
				throw new IllegalStateException("Impossible");
			} else
				throw new IllegalStateException("Unknown annotation type: " + lhsAnnotation.getClass().getName());
		}

		if (rhsIter.hasNext())
			return -1;
		else if (lhsIter.hasNext())
			return 1;
		else
			return 0;
	}

	/**
	 * @param exactBugPatternMatch The exactBugPatternMatch to set.
	 */
	public void setExactBugPatternMatch(boolean exactBugPatternMatch) {
		this.exactBugPatternMatch = exactBugPatternMatch;
	}

	/**
	 * @return Returns the exactBugPatternMatch.
	 */
	public boolean isExactBugPatternMatch() {
		return exactBugPatternMatch;
	}
}



// vim:ts=4
