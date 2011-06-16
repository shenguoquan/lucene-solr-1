package org.apache.lucene.search.spans;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.index.IndexReader.AtomicReaderContext;
import org.apache.lucene.index.IndexReader.ReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.util.TermContext;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

/**
 * Expert-only.  Public for use by other weight implementations
 */
public class SpanWeight extends Weight {
  protected Similarity similarity;
  protected Set<Term> terms;
  protected SpanQuery query;
  protected Similarity.Stats stats;

  public SpanWeight(SpanQuery query, IndexSearcher searcher)
    throws IOException {
    this.similarity = searcher.getSimilarityProvider().get(query.getField());
    this.query = query;
    
    terms=new TreeSet<Term>();
    query.extractTerms(terms);
    final ReaderContext context = searcher.getTopReaderContext();
    final TermContext states[] = new TermContext[terms.size()];
    int i = 0;
    for (Term term : terms)
      states[i++] = TermContext.build(context, term, true);
    stats = similarity.computeStats(searcher, query.getField(), query.getBoost(), states);
  }

  @Override
  public Query getQuery() { return query; }

  @Override
  public float getValueForNormalization() throws IOException {
    return stats.getValueForNormalization();
  }

  @Override
  public void normalize(float queryNorm, float topLevelBoost) {
    stats.normalize(queryNorm, topLevelBoost);
  }

  @Override
  public Scorer scorer(AtomicReaderContext context, ScorerContext scorerContext) throws IOException {
    return new SpanScorer(query.getSpans(context), this, similarity, similarity.sloppyDocScorer(stats, query.getField(), context));
  }

  @Override
  public Explanation explain(AtomicReaderContext context, int doc)
    throws IOException {
    //nocommit: fix explains
    if (!(similarity instanceof TFIDFSimilarity))
      return new ComplexExplanation();
    final TFIDFSimilarity similarity = (TFIDFSimilarity) this.similarity;
    final TFIDFSimilarity.IDFStats stats = (TFIDFSimilarity.IDFStats) this.stats;

    ComplexExplanation result = new ComplexExplanation();
    result.setDescription("weight("+getQuery()+" in "+doc+"), product of:");
    String field = ((SpanQuery)getQuery()).getField();

    Explanation idfExpl =
      new Explanation(stats.idf.getIdf(), "idf(" + field + ": " + stats.idf.explain() + ")");

    // explain query weight
    Explanation queryExpl = new Explanation();
    queryExpl.setDescription("queryWeight(" + getQuery() + "), product of:");

    Explanation boostExpl = new Explanation(getQuery().getBoost(), "boost");
    if (getQuery().getBoost() != 1.0f)
      queryExpl.addDetail(boostExpl);
    queryExpl.addDetail(idfExpl);

    Explanation queryNormExpl = new Explanation(stats.queryNorm,"queryNorm");
    queryExpl.addDetail(queryNormExpl);

    queryExpl.setValue(boostExpl.getValue() *
                       idfExpl.getValue() *
                       queryNormExpl.getValue());

    result.addDetail(queryExpl);

    // explain field weight
    ComplexExplanation fieldExpl = new ComplexExplanation();
    fieldExpl.setDescription("fieldWeight("+field+":"+query.toString(field)+
                             " in "+doc+"), product of:");

    Explanation tfExpl = ((SpanScorer)scorer(context, ScorerContext.def())).explain(doc);
    fieldExpl.addDetail(tfExpl);
    fieldExpl.addDetail(idfExpl);

    Explanation fieldNormExpl = new Explanation();
    byte[] fieldNorms = context.reader.norms(field);
    float fieldNorm =
      fieldNorms!=null ? similarity.decodeNormValue(fieldNorms[doc]) : 1.0f;
    fieldNormExpl.setValue(fieldNorm);
    fieldNormExpl.setDescription("fieldNorm(field="+field+", doc="+doc+")");
    fieldExpl.addDetail(fieldNormExpl);

    fieldExpl.setMatch(Boolean.valueOf(tfExpl.isMatch()));
    fieldExpl.setValue(tfExpl.getValue() *
                       idfExpl.getValue() *
                       fieldNormExpl.getValue());

    result.addDetail(fieldExpl);
    result.setMatch(fieldExpl.getMatch());

    // combine them
    result.setValue(queryExpl.getValue() * fieldExpl.getValue());

    if (queryExpl.getValue() == 1.0f)
      return fieldExpl;

    return result;
  }
}
