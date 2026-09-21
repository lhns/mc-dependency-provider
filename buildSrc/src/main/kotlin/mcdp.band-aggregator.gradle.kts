plugins {
    id("mcdp.shaded-jar")
    id("mcdp.maven-central")
}

val mcdpBand = the<McdpBandExtension>()

mavenPublishing {
    pom {
        // The project name *is* the artifactId (see `coordinates` in mcdp.maven-central),
        // and that is exactly what every band wants as the POM `<name>`.
        name.set(project.name)
        description.set(mcdpBand.pomDescription)
    }
}
