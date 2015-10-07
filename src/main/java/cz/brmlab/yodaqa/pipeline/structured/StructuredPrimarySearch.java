package cz.brmlab.yodaqa.pipeline.structured;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import cz.brmlab.yodaqa.flow.dashboard.AnswerIDGenerator;
import cz.brmlab.yodaqa.flow.dashboard.AnswerSourceStructured;
import cz.brmlab.yodaqa.flow.dashboard.QuestionDashboard;
import cz.brmlab.yodaqa.flow.dashboard.snippet.AnsweringProperty;
import cz.brmlab.yodaqa.flow.dashboard.snippet.SnippetIDGenerator;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.AbstractCas;
import org.apache.uima.fit.component.JCasMultiplier_ImplBase;
import org.apache.uima.fit.util.FSCollectionFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.IntegerArray;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.CasCopier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.brmlab.yodaqa.analysis.ansscore.AnswerFV;
import cz.brmlab.yodaqa.analysis.passextract.PassByClue;
import cz.brmlab.yodaqa.flow.asb.MultiThreadASB;
import cz.brmlab.yodaqa.analysis.ansscore.AF;
import cz.brmlab.yodaqa.model.CandidateAnswer.AnswerFeature;
import cz.brmlab.yodaqa.model.CandidateAnswer.AnswerInfo;
import cz.brmlab.yodaqa.model.CandidateAnswer.AnswerResource;
import cz.brmlab.yodaqa.model.Question.Clue;
import cz.brmlab.yodaqa.model.Question.ClueConcept;
import cz.brmlab.yodaqa.model.Question.ClueSubject;
import cz.brmlab.yodaqa.model.Question.Concept;
import cz.brmlab.yodaqa.model.SearchResult.ResultInfo;
import cz.brmlab.yodaqa.model.TyCor.LAT;
import cz.brmlab.yodaqa.provider.rdf.PropertyValue;
import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.NN;
import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;

/**
 * An abstract base class that from the QuestionCAS generates
 * a bunch of CandidateAnswerCAS instances using PropertyValue
 * data generated by querying structured sources.  Subclasses
 * of this represent handling of the specific structured sources.
 *
 * FIXME: We should have a common ancestor PrimarySearch which
 * generalizes processing of all primary searches. */

public abstract class StructuredPrimarySearch extends JCasMultiplier_ImplBase {
	protected Logger logger = LoggerFactory.getLogger(StructuredPrimarySearch.class);

	protected JCas questionView;
	protected Iterator<PropertyValue> relIter;
	protected int i;

	protected String sourceName;
	protected String clueFeaturePrefix;
	protected String noClueFeature;

	public StructuredPrimarySearch(String sourceName_,
			String clueFeaturePrefix_,
			String noClueFeature_) {
		sourceName = sourceName_;
		clueFeaturePrefix = clueFeaturePrefix_;
		noClueFeature = noClueFeature_;
	}

	@Override
	public void initialize(UimaContext aContext) throws ResourceInitializationException {
		super.initialize(aContext);
	}

	/** Retrieve properties associated with a given Concept. */
	protected abstract List<PropertyValue> getConceptProperties(JCas questionView, Concept concept);

	@Override
	public void process(JCas jcas) throws AnalysisEngineProcessException {
		questionView = jcas;

		List<PropertyValue> properties = new ArrayList<PropertyValue>();

		for (Concept concept : JCasUtil.select(questionView, Concept.class)) {
			properties.addAll(getConceptProperties(questionView, concept));
		}

		relIter = properties.iterator();
		i = 0;
	}


	@Override
	public boolean hasNext() throws AnalysisEngineProcessException {
		return relIter.hasNext() || i == 0;
	}

	@Override
	public AbstractCas next() throws AnalysisEngineProcessException {
		PropertyValue property = relIter.hasNext() ? relIter.next() : null;
		i++;

		JCas jcas = getEmptyJCas();
		try {
			jcas.createView("Question");
			JCas canQuestionView = jcas.getView("Question");
			copyQuestion(questionView, canQuestionView);

			jcas.createView("Answer");
			JCas canAnswerView = jcas.getView("Answer");
			if (property != null) {
				propertyToAnswer(canAnswerView, property, !relIter.hasNext() ? i : 0, questionView);
			} else {
				dummyAnswer(canAnswerView, i);
			}
		} catch (Exception e) {
			jcas.release();
			throw new AnalysisEngineProcessException(e);
		}
		return jcas;
	}

	protected void copyQuestion(JCas src, JCas dest) throws Exception {
		CasCopier copier = new CasCopier(src.getCas(), dest.getCas());
		copier.copyCasView(src.getCas(), dest.getCas(), true);
	}

	/** This is the workhorse that converts a given PropertyValue
	 * property to a candidate answer, to live in the given jcas.
	 * So we set the jcas text and generate a bunch of featuresets,
	 * especially AFs. */
	protected void propertyToAnswer(JCas jcas, PropertyValue property,
			int isLast, JCas questionView) throws Exception {
		logger.info(" FOUND: {} -- {}", property.getProperty(), property.getValue());

		jcas.setDocumentText(property.getValue());
		jcas.setDocumentLanguage("en"); // XXX

		String title = property.getObject() + " " + property.getProperty();
		AnswerSourceStructured as = makeAnswerSource(property);
		int sourceID = QuestionDashboard.getInstance().get(questionView).storeAnswerSource(as);
		AnsweringProperty ap = new AnsweringProperty(SnippetIDGenerator.getInstance().generateID(), sourceID, property.getProperty());
		QuestionDashboard.getInstance().get(questionView).addSnippet(ap);

		ResultInfo ri = new ResultInfo(jcas);
		ri.setDocumentTitle(title);
		ri.setSource(sourceName);
		ri.setRelevance(1.0);
		ri.setIsLast(isLast);
		ri.setSourceID(sourceID);
		ri.setOrigin(this.getClass().getCanonicalName());
		/* XXX: We ignore ansfeatures as we generate just
		 * a single answer here. */
		ri.addToIndexes();

		AnswerFV fv = new AnswerFV();
		fv.setFeature(AF.Occurences, 1.0);
		fv.setFeature(AF.ResultLogScore, Math.log(1 + ri.getRelevance()));
		fv.setFeature(property.getOriginFeat(), 1.0);
		if (property.getScore() != null)
			fv.setFeature(AF.PropertyScore, property.getScore());

		/* Mark by concept-clue-origin AFs. */
		addConceptFeatures(questionView, fv, property.getObject());

		/* Match clues in relation name (esp. LAT or Focus clue
		 * will be nice). */
		matchCluesInName(questionView, property.getProperty(), fv);

		/* Generate also an LAT for the answer right away. */
		addTypeLAT(jcas, fv, property.getProperty());

		AnswerInfo ai = new AnswerInfo(jcas);
		ai.setFeatures(fv.toFSArray(jcas));
		ai.setIsLast(1);
		ai.setAnswerID(AnswerIDGenerator.getInstance().generateID());
		ai.setSnippetIDs(new IntegerArray(jcas, 1));
		ai.setSnippetIDs(0,ap.getSnippetID());

		/* Generate a resource descriptor if available. */
		if (property.getValRes() != null) {
			AnswerResource ar = new AnswerResource(jcas);
			ar.setIri(property.getValRes());
			ar.addToIndexes();
			ArrayList<AnswerResource> ars = new ArrayList<>();
			ars.add(ar);
			ai.setResources(FSCollectionFactory.createFSArray(jcas, ars));
		}

		ai.addToIndexes();
	}

	protected void dummyAnswer(JCas jcas, int isLast) throws Exception {
		/* We will just generate a single dummy CAS
		 * to avoid flow breakage. */
		jcas.setDocumentText("");
		jcas.setDocumentLanguage("en"); // XXX

		ResultInfo ri = new ResultInfo(jcas);
		ri.setDocumentTitle("");
		ri.setOrigin(this.getClass().getCanonicalName());
		ri.setIsLast(i);
		ri.addToIndexes();

		AnswerInfo ai = new AnswerInfo(jcas);
		ai.setIsLast(1);
		ai.addToIndexes();
	}

	protected void addConceptFeatures(JCas questionView, AnswerFV fv, String text) {
		double bestSourceRr = 0, bestLabelRr = 0, bestScore = 0;
		// XXX: Carry the clue reference in PropertyValue.
		for (Concept concept : JCasUtil.select(questionView, Concept.class)) {
			if (!concept.getCookedLabel().toLowerCase().equals(text.toLowerCase()))
				continue;
			// We don't set this since all our clues have concept origin
			//afv.setFeature(AF.OriginConcept, 1.0);
			if (concept.getBySubject())
				fv.setFeature(AF.OriginConceptBySubject, 1.0);
			if (concept.getByLAT())
				fv.setFeature(AF.OriginConceptByLAT, 1.0);
			if (concept.getByNE())
				fv.setFeature(AF.OriginConceptByNE, 1.0);
			if (concept.getSourceRr() > bestSourceRr)
				bestSourceRr = concept.getSourceRr();
			if (concept.getLabelRr() > bestLabelRr)
				bestLabelRr = concept.getLabelRr();
			if (concept.getScore() > bestScore)
				bestScore = concept.getScore();
		}
		fv.setFeature(AF.OriginConcept_feat + AF._clueType_ConceptSourceRR, bestSourceRr);
		fv.setFeature(AF.OriginConcept_feat + AF._clueType_ConceptLabelRR, bestLabelRr);
		fv.setFeature(AF.OriginConcept_feat + AF._clueType_ConceptScore, bestScore);
	}

	/** Create a specific AnswerSource instance for the given concept. */
	protected abstract AnswerSourceStructured makeAnswerSource(PropertyValue property);

	/** Add an LAT of given type string. This should be an addTypeLAT()
	 * wrapper that also generates an LAT feature and LAT instance
	 * of the appropriate class. */
	protected abstract void addTypeLAT(JCas jcas, AnswerFV fv, String type) throws AnalysisEngineProcessException;

	protected void addTypeLAT(JCas jcas, AnswerFV fv, String type, LAT lat) throws AnalysisEngineProcessException {
		String ntype = type.toLowerCase();

		/* We have a synthetic noun(-ish), synthetize
		 * a POS tag for it. */
		int len = jcas.getDocumentText().length();
		POS pos = new NN(jcas);
		pos.setBegin(0);
		pos.setEnd(len);
		pos.setPosValue("NNS");
		pos.addToIndexes();

		addLAT(lat, 0, len, null, ntype, pos, 0, 0.0);

		logger.debug(".. Ans <<{}>> => Property LAT/0 {}", jcas.getDocumentText(), ntype);
	}

	protected void addLAT(LAT lat, int begin, int end, Annotation base, String text, POS pos, long synset, double spec) {
		lat.setBegin(begin);
		lat.setEnd(end);
		lat.setBase(base);
		lat.setPos(pos);
		lat.setText(text);
		lat.setSpecificity(spec);
		lat.setSynset(synset);
		lat.addToIndexes();
	}


	protected void matchCluesInName(JCas questionView, String name, AnswerFV fv) {
		boolean clueMatched = false;
		for (Clue clue : JCasUtil.select(questionView, Clue.class)) {
			if (!name.matches(PassByClue.getClueRegex(clue)))
				continue;
			clueMatched = true;
			clueAnswerFeatures(fv, clue);
		}
		if (!clueMatched)
			fv.setFeature(noClueFeature, -1.0);
	}

	/** Generate primary search kind specific features indicating
	 * the originating clue type. */
	protected void clueAnswerFeatures(AnswerFV afv, Clue clue) {
		afv.setFeature(clueFeaturePrefix + clue.getType().getShortName(), 1.0);
		if (clue instanceof ClueSubject) {
			afv.setFeature(clueFeaturePrefix + "ClueSubject", 1.0);
		} else if (clue instanceof ClueConcept ) {
			double bestSourceRr = 0, bestLabelRr = 0, bestScore = 0;
			for (Concept concept : FSCollectionFactory.create(((ClueConcept) clue).getConcepts(), Concept.class)) {
				if (concept.getBySubject())
					afv.setFeature(clueFeaturePrefix + "ClueSubject", 1.0);
				if (concept.getByLAT())
					afv.setFeature(clueFeaturePrefix + "ClueLAT", 1.0);
				if (concept.getByNE())
					afv.setFeature(clueFeaturePrefix + "ClueNE", 1.0);
				if (concept.getSourceRr() > bestSourceRr)
					bestSourceRr = concept.getSourceRr();
				if (concept.getLabelRr() > bestLabelRr)
					bestLabelRr = concept.getLabelRr();
				if (concept.getScore() > bestScore)
					bestScore = concept.getScore();
			}
			afv.setFeature(clueFeaturePrefix + AF._clueType_ConceptSourceRR, bestSourceRr);
			afv.setFeature(clueFeaturePrefix + AF._clueType_ConceptLabelRR, bestLabelRr);
			afv.setFeature(clueFeaturePrefix + AF._clueType_ConceptScore, bestScore);
		}
	}


	@Override
	public int getCasInstancesRequired() {
		return MultiThreadASB.maxJobs * 2;
	}
}
