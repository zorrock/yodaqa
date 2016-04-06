package cz.brmlab.yodaqa.analysis.question;

//import de.tudarmstadt.ukp.dkpro.core.berkeleyparser.BerkeleyParser;
//import de.tudarmstadt.ukp.dkpro.core.clearnlp.ClearNlpDependencyParser;
//import de.tudarmstadt.ukp.dkpro.core.clearnlp.ClearNlpLemmatizer;
//import de.tudarmstadt.ukp.dkpro.core.clearnlp.ClearNlpPosTagger;
//import de.tudarmstadt.ukp.dkpro.core.clearnlp.ClearNlpSemanticRoleLabeler;
import cz.brmlab.yodaqa.io.debug.DumpConstituents;
import de.tudarmstadt.ukp.dkpro.core.languagetool.LanguageToolLemmatizer;
import de.tudarmstadt.ukp.dkpro.core.opennlp.OpenNlpSegmenter;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.fit.component.CasDumpWriter;
import org.apache.uima.fit.factory.AggregateBuilder;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.resource.ResourceInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import de.tudarmstadt.ukp.dkpro.core.maltparser.MaltParser;
//import de.tudarmstadt.ukp.dkpro.core.matetools.MateLemmatizer;
//import de.tudarmstadt.ukp.dkpro.core.matetools.MateParser;
//import de.tudarmstadt.ukp.dkpro.core.matetools.MatePosTagger;
//import de.tudarmstadt.ukp.dkpro.core.opennlp.OpenNlpPosTagger;
//import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.StanfordLemmatizer;
//import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.StanfordNamedEntityRecognizer;
//import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.StanfordPosTagger;

/**
 * Annotate the QuestionCAS.
 *
 * This is an aggregate AE that will run a variety of annotators on the
 * QuestionCAS, preparing it for the PrimarySearch and AnswerGenerator
 * stages. */

public class QuestionAnalysisAE /* XXX: extends AggregateBuilder ? */ {
	final static Logger logger = LoggerFactory.getLogger(QuestionAnalysisAE.class);

	public static AnalysisEngineDescription createEngineDescription() throws ResourceInitializationException {
		AggregateBuilder builder = new AggregateBuilder();

		/* A bunch of DKpro-bound NLP processors (these are
		 * the giants we stand on the shoulders of) */

		/* XXX: Sorry for the mess below for now. We list all our
		 * alternatives for now, but will clean it up later. */

		/* Token features: */

		builder.add(AnalysisEngineFactory.createEngineDescription(OpenNlpSegmenter.class));


		/* POS, constituents, dependencies: */
		// fast, reliable
//		builder.add(AnalysisEngineFactory.createEngineDescription(StanfordParser.class,
//					StanfordParser.PARAM_WRITE_POS, true));

		// slow startup, no dependencies, superseded
		//builder.add(AnalysisEngineFactory.createEngineDescription(BerkeleyParser.class));

		/* POS features: */

		// Generated by BerkeleyParser
		// fastest:
		//builder.add(AnalysisEngineFactory.createEngineDescription(OpenNlpPosTagger.class));
		/*
		builder.add(AnalysisEngineFactory.createEngineDescription(StanfordPosTagger.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(MatePosTagger.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(ClearNlpPosTagger.class));
		*/

		/* Lemma features: */

		// fastest and handling numbers correctly:
		builder.add(AnalysisEngineFactory.createEngineDescription(LanguageToolLemmatizer.class));
		/*
		builder.add(AnalysisEngineFactory.createEngineDescription(StanfordLemmatizer.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(ClearNlpLemmatizer.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(MateLemmatizer.class));
		*/

		/* Dependency features: */
		// no need for now

		// fastest and correct!
		//builder.add(AnalysisEngineFactory.createEngineDescription(MaltParser.class));
		/*
		// just wrong (Who received the Nobel Prize for Physiology and Medicine in the year 2012?) - everything depends on medicine
		builder.add(AnalysisEngineFactory.createEngineDescription(MateParser.class));
		// a bit wrong (Who received the Nobel Prize for Physiology and Medicine in the year 2012?) - 2012 depends on received
		builder.add(AnalysisEngineFactory.createEngineDescription(ClearNlpDependencyParser.class));
		*/

		/* Named Entities: */
		//builder.add(OpenNlpNamedEntities.createEngineDescription());

		/*
		// too weak, we need very rich NE set
		builder.add(AnalysisEngineFactory.createEngineDescription(StanfordNamedEntityRecognizer.class));
		*/

		/* ...and misc extras: */
		builder.add(AnalysisEngineFactory.createEngineDescription(CzechPOSTagger.class));
		/*
		// too sparse to be useful
		builder.add(AnalysisEngineFactory.createEngineDescription(ClearNlpSemanticRoleLabeler.class));
		*/

		/* Okay! Now, we can proceed with our key tasks. */

		builder.add(AnalysisEngineFactory.createEngineDescription(FocusGenerator.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(FocusNameProxy.class));
		//builder.add(AnalysisEngineFactory.createEngineDescription(SubjectGenerator.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(SVGenerator.class));

		/* Prepare LATs */
		builder.add(AnalysisEngineFactory.createEngineDescription(LATByFocus.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(LATBySV.class));
		/* Generalize imprecise LATs */
		//builder.add(AnalysisEngineFactory.createEngineDescription(LATByWordnet.class,
		//			LATByWordnet.PARAM_EXPAND_SYNSET_LATS, false));

		/* Generate clues; the order is less specific to more specific */
		builder.add(AnalysisEngineFactory.createEngineDescription(ClueByTokenConstituent.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(ClueBySV.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(ClueByNE.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(ClueByLAT.class));
		builder.add(AnalysisEngineFactory.createEngineDescription(ClueBySubject.class));
		/* Convert some syntactic clues to concept clues */
		//builder.add(AnalysisEngineFactory.createEngineDescription(CluesToConcepts.class));
		/* Generate Concepts by Stepnicka*/
		builder.add(AnalysisEngineFactory.createEngineDescription(StepnickaToConcepts.class));
		/* Merge any duplicate clues */
		builder.add(AnalysisEngineFactory.createEngineDescription(CluesMergeByText.class));



		builder.add(AnalysisEngineFactory.createEngineDescription(DashboardHook.class));
		/* Classify question into classes*/
		//builder.add(AnalysisEngineFactory.createEngineDescription(ClassClassifier.class));
		/* Some debug dumps of the intermediate CAS. */
		if (logger.isDebugEnabled()) {
			builder.add(AnalysisEngineFactory.createEngineDescription(DumpConstituents.class));
			builder.add(AnalysisEngineFactory.createEngineDescription(
				CasDumpWriter.class,
				CasDumpWriter.PARAM_OUTPUT_FILE, "/tmp/yodaqa-qacas.txt"));
		}

		AnalysisEngineDescription aed = builder.createAggregateDescription();
		aed.getAnalysisEngineMetaData().setName("cz.brmlab.yodaqa.analysis.question.QuestionAnalysisAE");
		return aed;
	}
}
