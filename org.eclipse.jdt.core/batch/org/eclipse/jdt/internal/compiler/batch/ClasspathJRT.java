package org.eclipse.jdt.internal.compiler.batch;

import java.io.File;
import java.io.IOException;

import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipFile;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.classfmt.ModuleInfo;
import org.eclipse.jdt.internal.compiler.env.AccessRuleSet;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleEnvironment;
import org.eclipse.jdt.internal.compiler.env.IModuleLocation;
import org.eclipse.jdt.internal.compiler.env.IMultiModuleEntry;
import org.eclipse.jdt.internal.compiler.env.IMultiModulePackageLookup;
import org.eclipse.jdt.internal.compiler.env.IMultiModuleTypeLookup;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.env.PackageLookup;
import org.eclipse.jdt.internal.compiler.env.TypeLookup;
import org.eclipse.jdt.internal.compiler.lookup.ModuleEnvironment;
import org.eclipse.jdt.internal.compiler.util.JRTUtil;
import org.eclipse.jdt.internal.compiler.util.SuffixConstants;

@SuppressWarnings({"rawtypes", "unchecked"})
public class ClasspathJRT extends ClasspathLocation implements IMultiModuleEntry, IModuleEnvironment {
	protected File file;
	protected ZipFile zipFile;
	protected ZipFile annotationZipFile;
	protected boolean closeZipFileAtEnd;
	private static HashMap<String, Set<IModule>> ModulesCache = new HashMap<>();
	//private Set<String> packageCache;
	protected List<String> annotationPaths;

	protected Function<char[], TypeLookup> typeLookupForModule = modName -> 
			(typeName, qualifiedPackageName, qualifiedBinaryFileName, asBinaryOnly) -> {
		return typeLookup().findClass(typeName, qualifiedPackageName, qualifiedBinaryFileName, asBinaryOnly, modName);
	};

	protected Function<char[], PackageLookup> pkgLookupForModule = modName -> 
		qualifiedPackageName -> {
			return packageLookup().isPackage(qualifiedPackageName, modName);
		};
		
	public ClasspathJRT(File file, boolean closeZipFileAtEnd,
			AccessRuleSet accessRuleSet, String destinationPath) {
		super(accessRuleSet, destinationPath);
		this.file = file;
		this.closeZipFileAtEnd = closeZipFileAtEnd;
	}

	public List fetchLinkedJars(FileSystem.ClasspathSectionProblemReporter problemReporter) {
		return null;
	}
	public boolean isPackage(String qualifiedPackageName, Optional<char[]> module) {
		return JRTUtil.isPackage(this.file, qualifiedPackageName, module);
	}
	public NameEnvironmentAnswer findClass(char[] typeName, String qualifiedPackageName, String qualifiedBinaryFileName) {
		return findClass(typeName, qualifiedPackageName, qualifiedBinaryFileName, false, Optional.empty());
	}
	public NameEnvironmentAnswer findClass(char[] typeName, String qualifiedPackageName, String qualifiedBinaryFileName, boolean asBinaryOnly) {
		return typeLookup().findClass(typeName, qualifiedPackageName, qualifiedBinaryFileName, asBinaryOnly, Optional.empty());
	}
	public NameEnvironmentAnswer findClass(char[] typeName, String qualifiedPackageName, String qualifiedBinaryFileName, boolean asBinaryOnly, Optional<Collection<char[]>> mods) {
		if (!isPackage(qualifiedPackageName))
			return null; // most common case

		try {
			ClassFileReader reader = ClassFileReader.readFromModules(this.file, qualifiedBinaryFileName, mods);

			if (reader != null) {
				if (this.annotationPaths != null) {
					String qualifiedClassName = qualifiedBinaryFileName.substring(0, qualifiedBinaryFileName.length()-SuffixConstants.EXTENSION_CLASS.length()-1);
					for (String annotationPath : this.annotationPaths) {
						try {
							this.annotationZipFile = reader.setExternalAnnotationProvider(annotationPath, qualifiedClassName, this.annotationZipFile, null);
							if (reader.hasAnnotationProvider())
								break;
						} catch (IOException e) {
							// don't let error on annotations fail class reading
						}
					}
				}
				return new NameEnvironmentAnswer(reader, fetchAccessRestriction(qualifiedBinaryFileName));
			}
		} catch(ClassFormatException e) {
			// treat as if class file is missing
		} catch (IOException e) {
			// treat as if class file is missing
		}
		return null;
	}
	@Override
	public boolean hasAnnotationFileFor(String qualifiedTypeName) {
		return false; // TODO: Revisit 
	}
	public char[][][] findTypeNames(final String qualifiedPackageName, final IModule mod) {
		if (!isPackage(qualifiedPackageName))
			return null; // most common case
		final char[] packageArray = qualifiedPackageName.toCharArray();
		final ArrayList answers = new ArrayList();
	
		try {
			JRTUtil.walkModuleImage(this.file, new JRTUtil.JrtFileVisitor<java.nio.file.Path>() {

				@Override
				public FileVisitResult visitPackage(java.nio.file.Path dir, java.nio.file.Path modPath, BasicFileAttributes attrs) throws IOException {
					if (qualifiedPackageName.startsWith(dir.toString())) {
						return FileVisitResult.CONTINUE;	
					}
					return FileVisitResult.SKIP_SUBTREE;
				}

				@Override
				public FileVisitResult visitFile(java.nio.file.Path dir, java.nio.file.Path modPath, BasicFileAttributes attrs) throws IOException {
					if (!dir.getParent().toString().equals(qualifiedPackageName)) {
						return FileVisitResult.CONTINUE;
					}
					String fileName = dir.getName(dir.getNameCount() - 1).toString();
					// The path already excludes the folders and all the '/', hence the -1 for last index of '/'
					addTypeName(answers, fileName, -1, packageArray);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitModule(java.nio.file.Path modPath) throws IOException {
					if (mod == ModuleEnvironment.UNNAMED_MODULE)
						return FileVisitResult.CONTINUE;
					if (!CharOperation.equals(mod.name(), modPath.toString().toCharArray())) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

			}, JRTUtil.NOTIFY_ALL);
		} catch (IOException e) {
			// Ignore and move on
		}
		
		int size = answers.size();
		if (size != 0) {
			char[][][] result = new char[size][][];
			answers.toArray(result);
			return result;
		}
		return null;
	}

	protected void addTypeName(final ArrayList answers, String fileName, int last, char[] packageName) {
		int indexOfDot = fileName.lastIndexOf('.');
		if (indexOfDot != -1) {
			String typeName = fileName.substring(last + 1, indexOfDot);
			answers.add(
				CharOperation.arrayConcat(
					CharOperation.splitOn('/', packageName),
					typeName.toCharArray()));
		}
	}
	public void initialize() throws IOException {
		loadModules();
	}
//	public void acceptModule(IModule mod) {
//		if (this.isJrt) 
//			return;
//		this.module = mod;
//	}
	public void loadModules() {
		Set<IModule> cache = ModulesCache.get(this.file);

		if (cache == null) {
			try {
				org.eclipse.jdt.internal.compiler.util.JRTUtil.walkModuleImage(this.file,
						new org.eclipse.jdt.internal.compiler.util.JRTUtil.JrtFileVisitor<Path>() {

					@Override
					public FileVisitResult visitPackage(Path dir, Path mod, BasicFileAttributes attrs)
							throws IOException {
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path f, Path mod, BasicFileAttributes attrs)
							throws IOException {
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitModule(Path mod) throws IOException {
						try {
							ClasspathJRT.this.acceptModule(JRTUtil.getClassfileContent(ClasspathJRT.this.file, IModuleLocation.MODULE_INFO_CLASS, mod.toString()));
						} catch (ClassFormatException e) {
							e.printStackTrace();
						}
						return FileVisitResult.SKIP_SUBTREE;
					}
				}, JRTUtil.NOTIFY_MODULES);
			} catch (IOException e) {
				// TODO: BETA_JAVA9 Should report better
			}
		}
	}
	void acceptModule(ClassFileReader reader) {
		if (reader != null) {
			IModule moduleDecl = reader.getModuleDeclaration();
			if (moduleDecl != null) {
				Set<IModule> cache = ModulesCache.get(this.file);
				if (cache == null) {
					ModulesCache.put(new String(moduleDecl.name()), cache = new HashSet<IModule>());
				}
				cache.add(moduleDecl);
			}
			((ModuleInfo)moduleDecl).entry = this;
		}
		
	}
	void acceptModule(byte[] content) {
		if (content == null) 
			return;
		ClassFileReader reader = null;
		try {
			reader = new ClassFileReader(content, IModuleLocation.MODULE_INFO_CLASS.toCharArray(), null);
		} catch (ClassFormatException e) {
			e.printStackTrace();
		}
		if (reader != null) {
			acceptModule(reader);
		}
	}
//	protected void addToPackageCache(String fileName, boolean endsWithSep) {
//		int last = endsWithSep ? fileName.length() : fileName.lastIndexOf('/');
//		while (last > 0) {
//			// extract the package name
//			String packageName = fileName.substring(0, last);
//			if (this.packageCache.contains(packageName))
//				return;
//			this.packageCache.add(packageName);
//			last = packageName.lastIndexOf('/');
//		}
//	}
//	public synchronized boolean isPackage(String qualifiedPackageName) {
//		if (this.packageCache != null)
//			return this.packageCache.contains(qualifiedPackageName);
//
//		this.packageCache = new HashSet<>(41);
//		this.packageCache.add(Util.EMPTY_STRING);
//		
//			try {
//				JRTUtil.walkModuleImage(this.file, new JRTUtil.JrtFileVisitor<java.nio.file.Path>() {
//
//					@Override
//					public FileVisitResult visitPackage(java.nio.file.Path dir, java.nio.file.Path mod, BasicFileAttributes attrs) throws IOException {
//						addToPackageCache(dir.toString(), true);
//						return FileVisitResult.CONTINUE;
//					}
//
//					@Override
//					public FileVisitResult visitFile(java.nio.file.Path dir, java.nio.file.Path mod, BasicFileAttributes attrs) throws IOException {
//						return FileVisitResult.CONTINUE;
//					}
//
//					@Override
//					public FileVisitResult visitModule(java.nio.file.Path mod) throws IOException {
//						return FileVisitResult.CONTINUE;
//					}
//
//				}, JRTUtil.NOTIFY_PACKAGES);
//			} catch (IOException e) {
//				// Ignore and move on
//			}
//		return this.packageCache.contains(qualifiedPackageName);
//	}
	public void reset() {
		if (this.closeZipFileAtEnd) {
			if (this.zipFile != null) {
				try {
					this.zipFile.close();
				} catch(IOException e) {
					// ignore
				}
				this.zipFile = null;
			}
			if (this.annotationZipFile != null) {
				try {
					this.annotationZipFile.close();
				} catch(IOException e) {
					// ignore
				}
				this.annotationZipFile = null;
			}
		}
		if (this.annotationPaths != null) {
			//this.packageCache = null;
			this.annotationPaths = null;
		}
	}
	public String toString() {
		return "Classpath for jar file " + this.file.getPath(); //$NON-NLS-1$
	}
	public char[] normalizedPath() {
		if (this.normalizedPath == null) {
			String path2 = this.getPath();
			char[] rawName = path2.toCharArray();
			if (File.separatorChar == '\\') {
				CharOperation.replace(rawName, '\\', '/');
			}
			this.normalizedPath = CharOperation.subarray(rawName, 0, CharOperation.lastIndexOf('.', rawName));
		}
		return this.normalizedPath;
	}
	public String getPath() {
		if (this.path == null) {
			try {
				this.path = this.file.getCanonicalPath();
			} catch (IOException e) {
				// in case of error, simply return the absolute path
				this.path = this.file.getAbsolutePath();
			}
		}
		return this.path;
	}
	public int getMode() {
		return BINARY;
	}

	public IModule getModule(char[] moduleName) {
		Set<IModule> modules = ModulesCache.get(new String(moduleName));
		if (modules != null) {
			for (IModule mod : modules) {
				if (CharOperation.equals(mod.name(), moduleName))
						return mod;
			}
		}
		return null;
	}
	@Override
	public IMultiModuleTypeLookup typeLookup() {
		return this::findClass;
	}
	@Override
	public IMultiModulePackageLookup packageLookup() {
		return this::isPackage;
	}
	@Override
	public boolean servesModule(char[] mod) {
		return ModulesCache.containsKey(new String(mod));
	}

	@Override
	public void acceptModule(IModule module) {
		// do nothing
	}

	@Override
	public IModuleEnvironment getLookupEnvironmentFor(IModule module) {
		// 
		return new IModuleEnvironment() {
			
			@Override
			public TypeLookup typeLookup() {
				//
				return servesModule(module.name()) ? ClasspathJRT.this.typeLookupForModule.apply(module.name()) : TypeLookup.nullTypeLookup;
			}
			
			@Override
			public PackageLookup packageLookup() {
				//
				return servesModule(module.name()) ? ClasspathJRT.this.pkgLookupForModule.apply(module.name()) : PackageLookup.nullPkgLookup;
			}
		};
	}

	@Override
	public IModuleEnvironment getLookupEnvironment() {
		// TODO Auto-generated method stub
		return this;
	}
}