import { useState, type FormEvent } from 'react';
import { api } from '../api/endpoints';
import { ApiError, describeError } from '../api/client';
import type { Application, Project } from '../api/types';
import { useToast } from '../context/ToastContext';
import { Button } from './Button';
import { Field } from './Field';
import { Modal } from './Modal';

export const APP_NAME_PATTERN = /^[a-z0-9][a-z0-9-]{1,62}$/;
export const ENVIRONMENT_PATTERN = /^[a-z][a-z0-9-]{1,30}$/;

export function validateProjectName(name: string): string | null {
  const n = name.trim();
  if (n.length < 2 || n.length > 80) return 'Project names are 2–80 characters.';
  return null;
}

export function validateApplicationName(name: string): string | null {
  return APP_NAME_PATTERN.test(name.trim()) ? null : 'Use lowercase letters, digits and dashes (2–63 characters), starting with a letter or digit.';
}

export function validateEnvironment(env: string): string | null {
  return ENVIRONMENT_PATTERN.test(env.trim()) ? null : 'Use lowercase letters, digits and dashes (2–31 characters), starting with a letter.';
}

interface NewProjectProps {
  open: boolean;
  onClose: () => void;
  onCreated: (project: Project) => void;
}

/** ADMIN: POST /projects. A 409 (duplicate name) is shown on the name field. */
export function NewProjectModal({ open, onClose, onCreated }: NewProjectProps) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [touched, setTouched] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const nameError = touched ? validateProjectName(name) : null;

  const reset = () => {
    setName('');
    setDescription('');
    setTouched(false);
    setServerError(null);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (validateProjectName(name)) return;
    setBusy(true);
    setServerError(null);
    try {
      const project = await api.createProject({ name: name.trim(), description: description.trim() || undefined });
      toast.success(`Created project "${project.name}".`);
      reset();
      onCreated(project);
    } catch (err) {
      const message = describeError(err);
      if (err instanceof ApiError && err.status === 409) setServerError('A project with this name already exists.');
      else {
        setServerError(message);
        toast.error(message);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title="New project"
      onClose={() => {
        reset();
        onClose();
      }}
      footer={
        <>
          <Button
            onClick={() => {
              reset();
              onClose();
            }}
            disabled={busy}
          >
            Cancel
          </Button>
          <Button type="submit" form="new-project-form" variant="primary" loading={busy}>
            Create project
          </Button>
        </>
      }
    >
      <form id="new-project-form" className="stack" onSubmit={submit} noValidate>
        <Field label="Project name" hint="2–80 characters, unique." error={nameError ?? serverError}>
          {(p) => <input {...p} data-autofocus className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Claims Platform" />}
        </Field>
        <Field label="Description (optional)">
          {(p) => <textarea {...p} className="textarea" rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />}
        </Field>
      </form>
    </Modal>
  );
}

interface NewApplicationProps {
  open: boolean;
  projectId: number;
  onClose: () => void;
  onCreated: (app: Application) => void;
}

/** ADMIN: POST /projects/{id}/applications with the contract's name / environment patterns. */
export function NewApplicationModal({ open, projectId, onClose, onCreated }: NewApplicationProps) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [environment, setEnvironment] = useState('staging');
  const [description, setDescription] = useState('');
  const [touched, setTouched] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const nameError = touched ? validateApplicationName(name) : null;
  const envError = touched ? validateEnvironment(environment) : null;

  const reset = () => {
    setName('');
    setDisplayName('');
    setEnvironment('staging');
    setDescription('');
    setTouched(false);
    setServerError(null);
  };

  const close = () => {
    reset();
    onClose();
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (validateApplicationName(name) || validateEnvironment(environment)) return;
    setBusy(true);
    setServerError(null);
    try {
      const app = await api.createApplication(projectId, {
        name: name.trim(),
        displayName: displayName.trim() || undefined,
        environment: environment.trim(),
        description: description.trim() || undefined,
      });
      toast.success(`Added application "${app.displayName || app.name}". Create an API key so it can send telemetry.`);
      reset();
      onCreated(app);
    } catch (err) {
      const message = describeError(err);
      if (err instanceof ApiError && err.status === 409) setServerError('This application name already exists in that environment.');
      else {
        setServerError(message);
        toast.error(message);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Add application"
      onClose={close}
      footer={
        <>
          <Button onClick={close} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" form="new-application-form" variant="primary" loading={busy}>
            Add application
          </Button>
        </>
      }
    >
      <form id="new-application-form" className="stack" onSubmit={submit} noValidate>
        <Field label="Application name" hint="Used by the SDK, e.g. claims-service. Lowercase, digits and dashes." error={nameError ?? serverError}>
          {(p) => <input {...p} data-autofocus className="input mono" value={name} onChange={(e) => setName(e.target.value)} placeholder="claims-service" autoCapitalize="off" />}
        </Field>
        <Field label="Display name (optional)">
          {(p) => <input {...p} className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Claims Service" />}
        </Field>
        <Field label="Environment" hint="e.g. dev, staging, production." error={envError}>
          {(p) => <input {...p} className="input mono" value={environment} onChange={(e) => setEnvironment(e.target.value)} autoCapitalize="off" />}
        </Field>
        <Field label="Description (optional)">
          {(p) => <textarea {...p} className="textarea" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />}
        </Field>
      </form>
    </Modal>
  );
}
